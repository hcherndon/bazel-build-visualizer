package com.holtherndon.bazelviz.runner.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-007 in executable form: what gets added, what gets asked, what gets refused. */
class InstrumentationPlannerTest {

    private static final Path BAZEL = Path.of("/usr/bin/bazel");
    private static final Path CWD = Path.of("/repo");
    private static final String ENDPOINT = "grpc://127.0.0.1:54321";

    private final InstrumentationPlanner planner = new InstrumentationPlanner();

    @Test
    @DisplayName("a plain build gets the backend and action publication, and can launch")
    void plainBuild(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw,
                Optional.of(ENDPOINT)));

        assertThat(plan.canLaunch()).isTrue();
        assertThat(plan.injectedArgv()).containsExactly(
                "--bes_backend=" + ENDPOINT,
                "--bes_timeout=" + InstrumentationPlanner.BES_TIMEOUT_VALUE,
                "--build_event_publish_all_actions");
        assertThat(plan.effective().toArgv()).containsExactly(
                "/usr/bin/bazel", "build",
                "--bes_backend=" + ENDPOINT,
                "--bes_timeout=" + InstrumentationPlanner.BES_TIMEOUT_VALUE,
                "--build_event_publish_all_actions", "//...");
        assertThat(plan.original().toArgv()).containsExactly("/usr/bin/bazel", "build", "//...");
        assertThat(plan.sourceAvailability().isPlanned(DataSource.BES_ENVELOPE)).isTrue();
    }

    @Test
    @DisplayName("every added flag explains itself, as ADR-007 requires")
    void everyFlagIsExplained(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw,
                Optional.of(ENDPOINT)));

        assertThat(plan.addedFlags()).isNotEmpty();
        assertThat(plan.addedFlags()).allSatisfy(flag -> {
            assertThat(flag.reason()).isNotBlank();
            assertThat(flag.argv()).startsWith("--");
            assertThat(flag.overhead()).isNotNull();
        });
        // The one flag with no session without it is the one that cannot be
        // turned off; everything else can.
        assertThat(plan.addedFlags().stream()
                        .filter(flag -> flag.capability() == Capability.BES_BACKEND)
                        .findFirst()
                        .orElseThrow()
                        .userCanDisable())
                .isFalse();
        assertThat(plan.addedFlags().stream()
                        .filter(flag -> flag.capability() == Capability.PUBLISH_ALL_ACTIONS)
                        .findFirst()
                        .orElseThrow()
                        .userCanDisable())
                .isTrue();
    }

    @Test
    @DisplayName("an existing BES backend blocks the launch until the user chooses")
    void existingBackendBlocks(@TempDir Path raw) {
        PlanRequest request = PlanRequest.initial(
                parse("build", "--bes_backend=grpc://corp.example:443", "//..."),
                fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT));

        InstrumentationPlan plan = planner.plan(request);

        assertThat(plan.canLaunch()).isFalse();
        PlanConflict conflict = plan.mandatoryConflicts().get(0);
        assertThat(conflict.kind()).isEqualTo(PlanConflict.Kind.EXISTING_BES_BACKEND);
        // Plan 8.5 names exactly three ways out, and every one states its cost.
        assertThat(conflict.resolutions()).hasSize(3);
        assertThat(conflict.resolutions()).allSatisfy(resolution ->
                assertThat(resolution.consequence()).isNotBlank());
        assertThat(conflict.resolution(PlanConflict.RESOLUTION_REPLACE_BES)).isPresent();
        assertThat(conflict.resolution(PlanConflict.RESOLUTION_KEEP_BES_USE_FILE)).isPresent();
        assertThat(conflict.resolution(PlanConflict.RESOLUTION_CANCEL)).isPresent();
    }

    @Test
    @DisplayName("replacing the user's backend is recorded, with the approval that permitted it")
    void replacingIsRecorded(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                        parse("build", "--bes_backend=grpc://corp.example:443", "//..."),
                        fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT))
                .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND, PlanConflict.RESOLUTION_REPLACE_BES));

        assertThat(plan.canLaunch()).isTrue();
        assertThat(plan.replacedFlags()).hasSize(1);
        ReplacedFlag replaced = plan.replacedFlags().get(0);
        assertThat(replaced.original()).isEqualTo("--bes_backend=grpc://corp.example:443");
        assertThat(replaced.approvedBy()).isEqualTo(PlanConflict.RESOLUTION_REPLACE_BES);
        assertThat(replaced.reason()).contains("shadowed");

        // Ours is appended after theirs, and Bazel takes the last value.
        List<String> argv = plan.effective().toArgv();
        assertThat(argv.indexOf("--bes_backend=grpc://corp.example:443"))
                .isLessThan(argv.indexOf("--bes_backend=" + ENDPOINT));
    }

    @Test
    @DisplayName("keeping the user's backend captures through a local file instead")
    void keepingTheirBackendFallsBackToAFile(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                        parse("build", "--bes_backend=grpc://corp.example:443", "//..."),
                        fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT))
                .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND,
                        PlanConflict.RESOLUTION_KEEP_BES_USE_FILE));

        assertThat(plan.canLaunch()).isTrue();
        assertThat(plan.injectedArgv())
                .anyMatch(flag -> flag.startsWith("--build_event_binary_file="));
        // Two backends are never both injected: a second copy of the stream
        // doubles the write cost and proves nothing.
        assertThat(plan.injectedArgv()).noneMatch(flag -> flag.startsWith("--bes_backend="));
        assertThat(plan.expectedOutputs()).hasSize(1);
        // Compared as text, not with Path.startsWith: a plan is inert, so the
        // file it names does not exist yet, and AssertJ's path assertion
        // resolves the real path and would fail for the right reason at the
        // wrong time.
        assertThat(plan.expectedOutputs().get(0)).isAbsolute();
        assertThat(plan.expectedOutputs().get(0).toString())
                .startsWith(raw.toAbsolutePath().toString());

        AddedFlag file = plan.addedFlags().stream()
                .filter(flag -> flag.capability() == Capability.BEP_BINARY_FILE)
                .findFirst()
                .orElseThrow();
        assertThat(file.writesFile()).isPresent();
        assertThat(file.mayContainSensitiveData()).isTrue();
        assertThat(plan.sourceAvailability().entry(DataSource.BES_ENVELOPE).availability())
                .isEqualTo(SourceAvailability.Availability.DECLINED);
    }

    @Test
    @DisplayName("the file fallback bounds the upload too, or a kept backend can hang the build")
    void theFileFallbackStillGetsATimeout(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                        parse("build", "--bes_backend=grpc://corp.example:443", "//..."),
                        fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT))
                .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND,
                        PlanConflict.RESOLUTION_KEEP_BES_USE_FILE));

        // This is the one plan that leaves a foreign Build Event Service
        // backend on the command line, and it was the one plan with no
        // --bes_timeout: the injection used to live inside the embedded-backend
        // branch, which this path does not take. Bazel's default is 0s, which
        // means wait for ever, and a Bazel client waiting for ever holds the
        // workspace's command lock for exactly as long — so the next build,
        // and the 'clean' the user reaches for when it will not start, block
        // on a process they cannot see.
        assertThat(plan.injectedArgv())
                .contains("--bes_timeout=" + InstrumentationPlanner.BES_TIMEOUT_VALUE);

        AddedFlag timeout = plan.addedFlags().stream()
                .filter(flag -> flag.capability() == Capability.BES_TIMEOUT)
                .findFirst()
                .orElseThrow();
        // The reason names the backend that is actually at the far end, which
        // on this path is theirs and not ours.
        assertThat(timeout.reason()).contains("your own Build Event Service backend");
        assertThat(timeout.reason()).contains("clean");
        // Their upload may legitimately be slow, so the choice stays theirs.
        assertThat(timeout.userCanDisable()).isTrue();
    }

    @Test
    @DisplayName("a user's own --bes_timeout is never overridden, on either backend path")
    void theirTimeoutWins(@TempDir Path raw) {
        InstrumentationPlan embedded = planner.plan(PlanRequest.initial(
                parse("build", "--bes_timeout=10m", "//..."),
                fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT)));

        InstrumentationPlan fallback = planner.plan(PlanRequest.initial(
                        parse("build", "--bes_backend=grpc://corp.example:443",
                                "--bes_timeout=10m", "//..."),
                        fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT))
                .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND,
                        PlanConflict.RESOLUTION_KEEP_BES_USE_FILE));

        // They have expressed an intent about how long to wait. Appending ours
        // would win by last-value and Bazel would say nothing about the one it
        // shadowed, which is the silent override the contract forbids.
        assertThat(embedded.injectedArgv()).noneMatch(flag -> flag.startsWith("--bes_timeout="));
        assertThat(fallback.injectedArgv()).noneMatch(flag -> flag.startsWith("--bes_timeout="));
        assertThat(embedded.effective().toArgv()).contains("--bes_timeout=10m");
        assertThat(fallback.effective().toArgv()).contains("--bes_timeout=10m");
    }

    @Test
    @DisplayName("a vetoed timeout is shown and not applied, so the cost of vetoing it is visible")
    void aVetoedTimeoutIsStillExplained(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest
                .initial(parse("build", "//..."), fullCapabilities(), CapturePreset.LIVE_ESSENTIALS,
                        raw, Optional.of(ENDPOINT))
                .vetoing(Capability.BES_TIMEOUT));

        assertThat(plan.injectedArgv()).noneMatch(flag -> flag.startsWith("--bes_timeout="));
        AddedFlag timeout = plan.addedFlags().stream()
                .filter(flag -> flag.capability() == Capability.BES_TIMEOUT)
                .findFirst()
                .orElseThrow();
        assertThat(timeout.isApplied()).isFalse();
        assertThat(timeout.reason()).isNotBlank();
    }

    @Test
    @DisplayName("an existing destination file is not overwritten without saying so")
    void existingDestinationBlocks(@TempDir Path raw) throws Exception {
        java.nio.file.Files.writeString(
                raw.resolve(InstrumentationPlanner.FALLBACK_BEP_FILE), "someone else's data");

        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                        parse("build", "--bes_backend=grpc://corp.example:443", "//..."),
                        fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT))
                .resolving(PlanConflict.Kind.EXISTING_BES_BACKEND,
                        PlanConflict.RESOLUTION_KEEP_BES_USE_FILE));

        assertThat(plan.canLaunch()).isFalse();
        assertThat(plan.mandatoryConflicts()).anySatisfy(conflict ->
                assertThat(conflict.kind()).isEqualTo(PlanConflict.Kind.DESTINATION_EXISTS));
    }

    @Test
    @DisplayName("an unsupported capability is shown and explained, and not injected")
    void unsupportedCapabilitiesAreNotInjected(@TempDir Path raw) {
        BazelCapabilities withoutAllActions = capabilities(
                spec("bes_backend", "build"),
                spec("build_event_binary_file", "build"));

        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), withoutAllActions, CapturePreset.LIVE_ESSENTIALS, raw,
                Optional.of(ENDPOINT)));

        AddedFlag allActions = plan.addedFlags().stream()
                .filter(flag -> flag.capability() == Capability.PUBLISH_ALL_ACTIONS)
                .findFirst()
                .orElseThrow();
        assertThat(allActions.capabilityStatus()).isEqualTo(CapabilityStatus.UNSUPPORTED);
        assertThat(allActions.isApplied()).isFalse();
        assertThat(plan.injectedArgv()).doesNotContain("--build_event_publish_all_actions");
        assertThat(plan.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("an unprobed Bazel gets nothing injected, and is told apart from an incapable one")
    void unprobedBazelInjectsNothing(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."),
                BazelCapabilities.unprobed("the probe timed out"),
                CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT)));

        assertThat(plan.injectedArgv()).isEmpty();
        assertThat(plan.addedFlags()).allSatisfy(flag ->
                assertThat(flag.capabilityStatus()).isEqualTo(CapabilityStatus.UNKNOWN));
        // "We could not ask" is a different sentence from "your Bazel cannot".
        assertThat(plan.sourceAvailability().entry(DataSource.BES_ENVELOPE).reason())
                .contains("could not be probed");
    }

    @Test
    @DisplayName("a user's --nobuild_event_publish_all_actions is left alone and reported")
    void userDisabledActionPublication(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "--nobuild_event_publish_all_actions", "//..."),
                fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT)));

        assertThat(plan.injectedArgv()).doesNotContain("--build_event_publish_all_actions");
        assertThat(plan.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.kind()).isEqualTo(PlanConflict.Kind.ACTION_PUBLICATION_DISABLED);
            // Advisory: the user asked for this, so it does not block a launch.
            assertThat(conflict.mandatory()).isFalse();
        });
        assertThat(plan.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("a command that publishes no events cannot be launched")
    void nonInstrumentableCommandBlocks(@TempDir Path raw) {
        BazelCapabilities buildOnly = capabilities(spec("bes_backend", "build", "test", "run"));

        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("clean"), buildOnly, CapturePreset.LIVE_ESSENTIALS, raw, Optional.of(ENDPOINT)));

        assertThat(plan.canLaunch()).isFalse();
        assertThat(plan.mandatoryConflicts()).anySatisfy(conflict ->
                assertThat(conflict.kind()).isEqualTo(PlanConflict.Kind.COMMAND_NOT_INSTRUMENTABLE));
    }

    @Test
    @DisplayName("no BES endpoint is an error, not a silently uninstrumented build")
    void missingEndpointIsAnError(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.LIVE_ESSENTIALS, raw,
                Optional.empty()));

        assertThat(plan.canLaunch()).isFalse();
        assertThat(plan.errors()).anySatisfy(error -> assertThat(error).contains("not listening"));
    }

    @Test
    @DisplayName("a veto removes the flag without removing the explanation of what it cost")
    void vetoRemovesTheFlag(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest
                .initial(parse("build", "//..."), fullCapabilities(), CapturePreset.LIVE_ESSENTIALS,
                        raw, Optional.of(ENDPOINT))
                .vetoing(Capability.PUBLISH_ALL_ACTIONS));

        assertThat(plan.injectedArgv()).containsExactly(
                "--bes_backend=" + ENDPOINT,
                "--bes_timeout=" + InstrumentationPlanner.BES_TIMEOUT_VALUE);
        assertThat(plan.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("a preset naming sources this version cannot capture says so rather than implying it can")
    void unimplementedPresetSourcesAreDeclared(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.PERFORMANCE_DIAGNOSTICS,
                raw, Optional.of(ENDPOINT)));

        // aquery and cquery arrive in Phase 5. The execution log and the
        // profile were on this list until Phase 4 and are not any more.
        assertThat(plan.sourceAvailability().entry(DataSource.AQUERY).reason())
                .contains("does not capture it yet");
        assertThat(plan.warnings()).anyMatch(warning -> warning.contains("does not capture yet"));
        assertThat(plan.canLaunch()).isTrue();
    }

    @Test
    @DisplayName("the execution log and the profile are planned, with the flags that make them usable")
    void enrichmentSourcesArePlanned(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.PERFORMANCE_DIAGNOSTICS,
                raw, Optional.of(ENDPOINT)));

        assertThat(plan.sourceAvailability().entry(DataSource.EXECUTION_LOG).availability())
                .isEqualTo(SourceAvailability.Availability.PLANNED);
        assertThat(plan.sourceAvailability().entry(DataSource.PROFILE).availability())
                .isEqualTo(SourceAvailability.Availability.PLANNED);

        List<String> flags = plan.addedFlags().stream().map(AddedFlag::argv).toList();
        assertThat(flags).anyMatch(flag -> flag.startsWith("--execution_log_compact_file="));
        assertThat(flags).anyMatch(flag -> flag.startsWith("--profile="));
        // Without these the profile is not worth importing: slimming is the
        // default and cuts per-action events from about thirty to two (X3),
        // and the primary output is the only thing tying a span to an action
        // (P4).
        assertThat(flags).contains("--noslim_profile");
        assertThat(flags).contains("--experimental_profile_include_primary_output");
    }

    @Test
    @DisplayName("only one execution-log format is ever asked for")
    void oneExecutionLogFormatOnly(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), fullCapabilities(), CapturePreset.PERFORMANCE_DIAGNOSTICS,
                raw, Optional.of(ENDPOINT)));

        // From Bazel 7 on, naming two is a command-line error that fails the
        // build before analysis (X2).
        assertThat(plan.addedFlags().stream()
                        .map(AddedFlag::argv)
                        .filter(flag -> flag.contains("execution_log_") && flag.contains("_file="))
                        .toList())
                .hasSize(1);
    }

    @Test
    @DisplayName("a user's own execution-log flag is left alone rather than fought with")
    void theirExecutionLogWins(@TempDir Path raw) {
        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "--execution_log_json_file=/tmp/theirs.json", "//..."),
                fullCapabilities(), CapturePreset.PERFORMANCE_DIAGNOSTICS,
                raw, Optional.of(ENDPOINT)));

        // Adding ours alongside theirs fails the build outright; the formats
        // are mutually exclusive, so this is not a shadowing problem but a
        // hard error.
        assertThat(plan.addedFlags().stream().map(AddedFlag::argv))
                .noneMatch(flag -> flag.contains("execution_log_compact_file"));
        assertThat(plan.sourceAvailability().entry(DataSource.EXECUTION_LOG).availability())
                .isEqualTo(SourceAvailability.Availability.DECLINED);
        assertThat(plan.warnings())
                .anyMatch(warning -> warning.contains("refuses more than one format"));
        assertThat(plan.sourceAvailability().entry(DataSource.EXECUTION_LOG).reason())
                .contains("only one format at a time");
    }

    @Test
    @DisplayName("Bazel 6.5.0 gets the binary format and the spawn-metrics flag")
    void sixFiveGetsTheLegacyPath(@TempDir Path raw) {
        BazelCapabilities six = capabilities(
                spec("bes_backend", "build", "test", "run"),
                spec("build_event_publish_all_actions", "build", "test", "run"),
                spec("execution_log_binary_file", "build", "test"),
                spec("experimental_execution_log_spawn_metrics", "build", "test"),
                spec("generate_json_trace_profile", "build", "test"),
                spec("profile", "build", "test"));

        InstrumentationPlan plan = planner.plan(PlanRequest.initial(
                parse("build", "//..."), six, CapturePreset.PERFORMANCE_DIAGNOSTICS,
                raw, Optional.of(ENDPOINT)));

        List<String> flags = plan.addedFlags().stream().map(AddedFlag::argv).toList();
        // 6.5.0 has no compact format (X1), and without the metrics flag its
        // log records what ran and not how long it took (S2).
        assertThat(flags).anyMatch(flag -> flag.startsWith("--execution_log_binary_file="));
        assertThat(flags).contains("--experimental_execution_log_spawn_metrics");
    }

    // ------------------------------------------------------------------ setup

    private static BazelCommand parse(String... args) {
        return new CommandLineParser().parse(BAZEL, CWD, List.of(args));
    }

    private static FlagSpec spec(String name, String... commands) {
        return new FlagSpec(
                name, Set.of(commands), true, false, Optional.of(true), Optional.empty(), List.of());
    }

    private static BazelCapabilities capabilities(FlagSpec... specs) {
        Map<String, FlagSpec> byName = new LinkedHashMap<>();
        for (FlagSpec spec : specs) {
            byName.put(spec.name(), spec);
        }
        return BazelCapabilities.fromFlags(
                "bazel 9.2.0", Optional.of("9.2.0"),
                BazelCapabilities.DetectionMethod.FLAGS_PROTO, byName, List.of());
    }

    private static BazelCapabilities fullCapabilities() {
        return capabilities(
                spec("bes_backend", "build", "test", "run"),
                spec("bes_lifecycle_events", "build", "test", "run"),
                spec("bes_timeout", "build", "test", "run"),
                spec("build_event_binary_file", "build", "test", "run"),
                spec("build_event_json_file", "build", "test", "run"),
                spec("build_event_publish_all_actions", "build", "test", "run"),
                spec("execution_log_compact_file", "build", "test"),
                spec("execution_log_binary_file", "build", "test"),
                spec("execution_log_json_file", "build", "test"),
                spec("generate_json_trace_profile", "build", "test"),
                spec("slim_profile", "build", "test"),
                spec("experimental_profile_include_primary_output", "build", "test"),
                spec("experimental_profile_include_target_label", "build", "test"),
                spec("profile", "build", "test"),
                spec("output", "aquery", "cquery"));
    }
}

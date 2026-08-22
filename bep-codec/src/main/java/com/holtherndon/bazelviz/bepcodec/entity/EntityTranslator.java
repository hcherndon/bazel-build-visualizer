package com.holtherndon.bazelviz.bepcodec.entity;

import com.google.devtools.build.lib.actions.cache.Protos.ActionCacheStatistics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Aborted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ActionExecuted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildFinished;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Configuration;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.NamedSetOfFiles;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestSummary;
import com.holtherndon.bazelviz.core.domain.TestOutcome;
import com.holtherndon.bazelviz.core.entity.ActionTiming;
import com.holtherndon.bazelviz.core.entity.EntityCommand;
import com.holtherndon.bazelviz.core.entity.FileRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Turns one decoded {@link BuildEvent} into the entity commands it implies.
 *
 * <p>Stateless and side-effect free: the same event always produces the same
 * commands, so re-normalizing a journal after an interruption cannot diverge
 * from an uninterrupted run, and every rule below is testable against a
 * hand-built event with no database in sight.
 *
 * <h2>Two rules that look like details and are not</h2>
 *
 * <p><b>Dispatch on the payload, read identity from the id.</b> The id key and
 * the payload key of the same event do not match — {@code buildFinished} carries
 * {@code finished}, {@code targetConfigured} carries {@code configured} — and
 * {@code aborted} rides four different id kinds. So the payload's oneof decides
 * what kind of thing happened, and the id supplies which thing it happened to.
 * Reading a configuration from an action's payload rather than its id, for
 * instance, disagrees with the id often enough that asserting equality would
 * throw on ordinary builds.
 *
 * <p><b>Empty string is how the wire says absent.</b> proto3 omits a string at
 * its default, so {@code ""} and "not set" are the same bytes. Every place that
 * matters here — an action's label, a target's kind, a configuration's cpu —
 * maps empty to {@code Optional.empty()} rather than storing a blank that would
 * later read as a real value named "".
 */
public final class EntityTranslator {

    /** The option that decides whether successful actions appear in the stream at all. */
    private static final String PUBLISH_ALL_ACTIONS = "--build_event_publish_all_actions";

    private static final String NO_PUBLISH_ALL_ACTIONS = "--nobuild_event_publish_all_actions";

    /**
     * @return the commands this event implies, in the order they must be
     *     applied; empty for the many events that describe the stream rather
     *     than the build (progress with no output, patterns, workspace status,
     *     convenience symlinks)
     */
    public List<EntityCommand> translate(BuildEvent event) {
        List<EntityCommand> commands = fromPayload(event);
        if (!event.getLastMessage()) {
            return commands;
        }
        // The end marker is a flag on the envelope, not a payload, and the
        // event carrying it changed between 7.6.1 and 8.4.1 -- so it is read
        // here rather than from any particular event type.
        List<EntityCommand> withEnd = new ArrayList<>(commands.size() + 1);
        withEnd.addAll(commands);
        withEnd.add(new EntityCommand.StreamEnded());
        return withEnd;
    }

    private List<EntityCommand> fromPayload(BuildEvent event) {
        return switch (event.getPayloadCase()) {
            case STARTED -> List.of(started(event.getStarted()));
            case OPTIONS_PARSED -> List.of(optionsParsed(event));
            case FINISHED -> List.of(finished(event.getFinished()));
            case CONFIGURATION -> configuration(event);
            case CONFIGURED -> configured(event);
            case COMPLETED -> completed(event);
            case NAMED_SET_OF_FILES -> namedSet(event);
            case ACTION -> action(event);
            case TEST_RESULT -> testResult(event);
            case TEST_SUMMARY -> testSummary(event);
            case BUILD_METRICS -> List.of(buildMetrics(event.getBuildMetrics()));
            case ABORTED -> aborted(event);
            case PROGRESS -> progress(event);
            default -> List.of();
        };
    }

    // --- build level ------------------------------------------------------

    private EntityCommand started(BuildStarted started) {
        return new EntityCommand.InvocationStarted(
                started.getUuid(),
                started.getBuildToolVersion(),
                started.getCommand(),
                started.getWorkingDirectory(),
                started.getWorkspaceDirectory(),
                started.getOptionsDescription(),
                started.getServerPid(),
                ProtoTimes.micros(
                        started.hasStartTime(), started.getStartTime(), started.getStartTimeMillis()));
    }

    /**
     * Reads the one option the actions view has to know about.
     *
     * <p>{@code cmd_line} is the effective list after rc files and expansions,
     * so an option set in a {@code .bazelrc} is visible here where scanning the
     * user's typed arguments would miss it. The last occurrence wins, matching
     * Bazel's own last-flag-wins resolution.
     */
    private EntityCommand optionsParsed(BuildEvent event) {
        boolean publishes = false;
        for (String option : event.getOptionsParsed().getCmdLineList()) {
            if (option.equals(PUBLISH_ALL_ACTIONS) || option.equals(PUBLISH_ALL_ACTIONS + "=true")) {
                publishes = true;
            } else if (option.equals(NO_PUBLISH_ALL_ACTIONS)
                    || option.equals(PUBLISH_ALL_ACTIONS + "=false")) {
                publishes = false;
            }
        }
        return new EntityCommand.InvocationOptions(publishes);
    }

    /**
     * The build's result.
     *
     * <p>Success comes from the exit code, not from {@code overall_success}:
     * proto3 omits that field on failure, so it is {@code false} for a failed
     * build and {@code false} again for a build whose event never said. The
     * exit code is present in both spellings on every measured version, and
     * "code 0" is a positive statement.
     */
    private EntityCommand finished(BuildFinished finished) {
        BuildFinished.ExitCode exitCode = finished.getExitCode();
        return new EntityCommand.InvocationFinished(
                exitCode.getName(),
                exitCode.getCode(),
                exitCode.getCode() == 0,
                ProtoTimes.micros(
                        finished.hasFinishTime(),
                        finished.getFinishTime(),
                        finished.getFinishTimeMillis()));
    }

    private EntityCommand buildMetrics(BuildMetrics metrics) {
        BuildMetrics.ActionSummary actions = metrics.getActionSummary();
        boolean hasActions = metrics.hasActionSummary();
        ActionCacheStatistics cache = actions.getActionCacheStatistics();
        boolean hasCache = actions.hasActionCacheStatistics();
        BuildMetrics.TargetMetrics targets = metrics.getTargetMetrics();
        boolean hasTargets = metrics.hasTargetMetrics();
        BuildMetrics.TimingMetrics timing = metrics.getTimingMetrics();
        boolean hasTiming = metrics.hasTimingMetrics();

        List<EntityCommand.MnemonicWork> mnemonics = new ArrayList<>();
        for (BuildMetrics.ActionSummary.ActionData data : actions.getActionDataList()) {
            mnemonics.add(new EntityCommand.MnemonicWork(
                    data.getMnemonic(),
                    // Bazel 6.5.0 and 7.6.1 do not populate the per-mnemonic
                    // created count at all, so proto3 hands back a zero that
                    // means "this version cannot say" (M4). Executed is
                    // populated on every version, so a zero there is a real
                    // zero and is kept.
                    countInsideSubMessage(data.getActionsCreated()),
                    OptionalLong.of(data.getActionsExecuted())));
        }
        List<EntityCommand.RunnerWork> runners = new ArrayList<>();
        for (BuildMetrics.ActionSummary.RunnerCount runner : actions.getRunnerCountList()) {
            runners.add(new EntityCommand.RunnerWork(
                    runner.getName(),
                    optional(runner.getExecKind()),
                    runner.getCount(),
                    "total".equals(runner.getName())));
        }
        List<EntityCommand.CacheMiss> misses = new ArrayList<>();
        for (ActionCacheStatistics.MissDetail detail : cache.getMissDetailsList()) {
            // Bazel emits a leading all-defaults entry. Storing it would add a
            // miss reason of "DIFFERENT_ACTION_KEY, 0 times" to every build.
            if (detail.getCount() == 0) {
                continue;
            }
            misses.add(new EntityCommand.CacheMiss(detail.getReason().name(), detail.getCount()));
        }
        List<EntityCommand.Garbage> garbage = new ArrayList<>();
        for (BuildMetrics.MemoryMetrics.GarbageMetrics gc :
                metrics.getMemoryMetrics().getGarbageMetricsList()) {
            garbage.add(new EntityCommand.Garbage(gc.getType(), gc.getGarbageCollected()));
        }

        return new EntityCommand.BuildMetricsReported(
                count(hasActions, actions.getActionsCreated()),
                count(hasActions, actions.getActionsExecuted()),
                count(hasCache, cache.getHits()),
                count(hasCache, cache.getMisses()),
                // targetMetrics and packageMetrics arrive as empty objects on
                // any loading or analysis failure (M6): the sub-message is
                // present and every field inside it is absent, so `has` is true
                // and the value is a zero that means "not counted". A build
                // that configured nothing really does report zero, and a build
                // that failed during analysis reports the same zero -- so the
                // ambiguity resolves to unknown, which is the direction that
                // cannot state a falsehood.
                countInsideSubMessage(hasTargets, targets.getTargetsLoaded()),
                countInsideSubMessage(hasTargets, targets.getTargetsConfigured()),
                countInsideSubMessage(
                        metrics.hasPackageMetrics(),
                        metrics.getPackageMetrics().getPackagesLoaded()),
                span(hasTiming, timing.getWallTimeInMs()),
                span(hasTiming, timing.getCpuTimeInMs()),
                span(hasTiming, timing.getAnalysisPhaseTimeInMs()),
                span(hasTiming, timing.getExecutionPhaseTimeInMs()),
                span(hasTiming, timing.getActionsExecutionStartInMs()),
                timing.hasCriticalPathTime()
                        ? OptionalLong.of(ProtoTimes.micros(timing.getCriticalPathTime()))
                        : OptionalLong.empty(),
                mnemonics,
                runners,
                misses,
                garbage);
    }

    /**
     * A count inside a sub-message the stream carried.
     *
     * <p>Zero is kept: a warm rebuild really does execute zero actions, and
     * refusing to say so would be its own kind of untruth.
     */
    private static OptionalLong count(boolean present, long value) {
        return present ? OptionalLong.of(value) : OptionalLong.empty();
    }

    /**
     * A count whose zero cannot be told apart from an absence.
     *
     * <p>Used where the containing sub-message is present but its fields are
     * not — {@code targetMetrics: {}} on an analysis failure, the per-mnemonic
     * created count on Bazel 6.5.0 and 7.6.1. A zero there is proto3's default
     * for a field nothing wrote, and reporting "0 targets configured" for a
     * build that configured thousands is the falsehood this avoids. The cost is
     * that a genuine zero also reads as unknown, which is the safe direction.
     */
    private static OptionalLong countInsideSubMessage(boolean present, long value) {
        return present && value != 0L ? OptionalLong.of(value) : OptionalLong.empty();
    }

    /** As above, where the sub-message's presence is already established. */
    private static OptionalLong countInsideSubMessage(long value) {
        return value == 0L ? OptionalLong.empty() : OptionalLong.of(value);
    }

    /**
     * A duration inside a sub-message the stream carried.
     *
     * <p>Zero is <em>not</em> kept, and this is the one place the asymmetry
     * with {@link #count} is deliberate. {@code executionPhaseTimeInMs} is
     * absent on Bazel 6.5.0, and on the wire an absent int64 and a zero are the
     * same bytes — so a zero here cannot be distinguished from "this version
     * does not report it". Reporting "the execution phase took 0 ms" is a
     * stronger false statement than reporting nothing, so the ambiguity
     * resolves to unknown.
     */
    private static OptionalLong span(boolean present, long millis) {
        return present && millis != 0L ? OptionalLong.of(millis) : OptionalLong.empty();
    }

    // --- configurations ---------------------------------------------------

    private List<EntityCommand> configuration(BuildEvent event) {
        String id = event.getId().getConfiguration().getId();
        if (id.isEmpty()) {
            return List.of();
        }
        Configuration payload = event.getConfiguration();
        return List.of(new EntityCommand.ConfigurationDeclared(
                id,
                payload.getMnemonic(),
                payload.getPlatformName(),
                payload.getCpu(),
                payload.getIsTool(),
                payload.getMakeVariableMap()));
    }

    // --- targets ----------------------------------------------------------

    private List<EntityCommand> configured(BuildEvent event) {
        BuildEventId.TargetConfiguredId id = event.getId().getTargetConfigured();
        if (id.getLabel().isEmpty()) {
            return List.of();
        }
        var payload = event.getConfigured();
        return List.of(new EntityCommand.TargetConfigured(
                id.getLabel(),
                optional(id.getAspect()),
                optional(payload.getTargetKind()),
                // TestSize's zero value is UNKNOWN, which is genuinely "not a
                // test or not stated" rather than a size, so it is dropped
                // instead of stored as the word UNKNOWN.
                payload.getTestSizeValue() == 0
                        ? Optional.empty()
                        : Optional.of(payload.getTestSize().name()),
                payload.getTagList()));
    }

    private List<EntityCommand> completed(BuildEvent event) {
        BuildEventId.TargetCompletedId id = event.getId().getTargetCompleted();
        if (id.getLabel().isEmpty()) {
            return List.of();
        }
        TargetComplete payload = event.getCompleted();
        List<EntityCommand.OutputGroupRef> groups = new ArrayList<>();
        for (OutputGroup group : payload.getOutputGroupList()) {
            // An output group can name several file sets. The schema stores one
            // root per row, so several sets become several rows under the same
            // name -- none is dropped, which a single root_depset_id column
            // would have forced.
            if (group.getFileSetsList().isEmpty()) {
                groups.add(new EntityCommand.OutputGroupRef(
                        group.getName(), Optional.empty(), group.getIncomplete()));
                continue;
            }
            for (BuildEventId.NamedSetOfFilesId set : group.getFileSetsList()) {
                groups.add(new EntityCommand.OutputGroupRef(
                        group.getName(), Optional.of(set.getId()), group.getIncomplete()));
            }
        }
        List<FileRef> directories = new ArrayList<>();
        for (File file : payload.getDirectoryOutputList()) {
            fileRef(file, true).ifPresent(directories::add);
        }
        return List.of(new EntityCommand.TargetCompleted(
                id.getLabel(),
                optional(id.getAspect()),
                id.getConfiguration().getId(),
                payload.getSuccess(),
                payload.getTagList(),
                testTimeoutSeconds(payload),
                groups,
                directories,
                FailureDetailReader.read(payload.getFailureDetail())));
    }

    /**
     * The effective test timeout, from whichever spelling this Bazel used.
     *
     * <p>Present only under {@code bazel test}: a test target built by
     * {@code bazel build} carries neither field, so absence here is not evidence
     * that a target is not a test.
     */
    private static OptionalLong testTimeoutSeconds(TargetComplete payload) {
        if (payload.hasTestTimeout()) {
            return OptionalLong.of(payload.getTestTimeout().getSeconds());
        }
        long seconds = payload.getTestTimeoutSeconds();
        return seconds == 0L ? OptionalLong.empty() : OptionalLong.of(seconds);
    }

    // --- file sets --------------------------------------------------------

    private List<EntityCommand> namedSet(BuildEvent event) {
        String id = event.getId().getNamedSet().getId();
        if (id.isEmpty()) {
            return List.of();
        }
        NamedSetOfFiles payload = event.getNamedSetOfFiles();
        List<String> children = new ArrayList<>(payload.getFileSetsCount());
        for (BuildEventId.NamedSetOfFilesId child : payload.getFileSetsList()) {
            children.add(child.getId());
        }
        List<FileRef> files = new ArrayList<>(payload.getFilesCount());
        for (File file : payload.getFilesList()) {
            fileRef(file, false).ifPresent(files::add);
        }
        return List.of(new EntityCommand.DepsetDeclared(id, children, files));
    }

    // --- actions ----------------------------------------------------------

    private List<EntityCommand> action(BuildEvent event) {
        BuildEventId.ActionCompletedId id = event.getId().getActionCompleted();
        String primaryOutput = id.getPrimaryOutput();
        if (primaryOutput.isEmpty()) {
            // Without the id's primary output there is no identity, and an
            // action row keyed on anything else would merge with its siblings.
            // The raw event is still in the journal.
            return List.of();
        }
        ActionExecuted payload = event.getAction();
        return List.of(new EntityCommand.ActionCompleted(
                primaryOutput,
                // The id's label, not the payload's: the payload field is
                // deprecated and the id is what the stream keys on. Both are
                // legitimately absent for the workspace-status action.
                optional(id.getLabel()),
                id.getConfiguration().getId(),
                optional(payload.getType()),
                payload.getSuccess(),
                // exit_code is only meaningful on a failure; on success proto3
                // omits it and a stored 0 would look like a reported result.
                payload.getSuccess() ? OptionalInt.empty() : OptionalInt.of(payload.getExitCode()),
                FailureDetailReader.read(payload.getFailureDetail()),
                ActionTiming.of(
                        payload.hasStartTime()
                                ? OptionalLong.of(ProtoTimes.micros(payload.getStartTime()))
                                : OptionalLong.empty(),
                        payload.hasEndTime()
                                ? OptionalLong.of(ProtoTimes.micros(payload.getEndTime()))
                                : OptionalLong.empty()),
                payload.getCommandLineList(),
                uriOf(payload.hasStdout(), payload.getStdout()),
                uriOf(payload.hasStderr(), payload.getStderr())));
    }

    private static Optional<FileRef> fileRef(File file, boolean directory) {
        return FileRef.of(
                file.getName(),
                file.getPathPrefixList(),
                file.getDigest(),
                file.getLength(),
                file.getUri(),
                directory);
    }

    private static Optional<String> uriOf(boolean present, File file) {
        return present ? optional(file.getUri()) : Optional.empty();
    }

    // --- tests ------------------------------------------------------------

    private List<EntityCommand> testResult(BuildEvent event) {
        BuildEventId.TestResultId id = event.getId().getTestResult();
        if (id.getLabel().isEmpty()) {
            return List.of();
        }
        TestResult payload = event.getTestResult();
        TestResult.ExecutionInfo execution = payload.getExecutionInfo();
        List<EntityCommand.TestLogRef> outputs = new ArrayList<>();
        for (File file : payload.getTestActionOutputList()) {
            if (file.getUri().isEmpty()) {
                continue;
            }
            outputs.add(new EntityCommand.TestLogRef(
                    optional(file.getName()), file.getUri(), Optional.empty()));
        }
        return List.of(new EntityCommand.TestAttemptCompleted(
                id.getLabel(),
                id.getConfiguration().getId(),
                // Bazel omits these when they are 1, and an unset int32 reads
                // as 0. A stored 0 would give the first attempt a key no other
                // event shares and split one test's history in two.
                atLeastOne(id.getRun()),
                atLeastOne(id.getShard()),
                atLeastOne(id.getAttempt()),
                TestOutcome.ofBazelStatus(payload.getStatus().name()),
                payload.getCachedLocally(),
                ProtoTimes.micros(
                        payload.hasTestAttemptStart(),
                        payload.getTestAttemptStart(),
                        payload.getTestAttemptStartMillisEpoch()),
                ProtoTimes.micros(
                        payload.hasTestAttemptDuration(),
                        payload.getTestAttemptDuration(),
                        payload.getTestAttemptDurationMillis()),
                // Zero is read as absent: Bazel 6.5.0 and 7.6.1 cannot report
                // this at all, and on 8.4.1/9.2.0 a passing test omits it. The
                // status already says whether the test passed, so nothing is
                // lost, and inventing a 0 for two versions that never sent one
                // would be a number the build did not produce.
                execution.getExitCode() == 0
                        ? OptionalInt.empty()
                        : OptionalInt.of(execution.getExitCode()),
                optional(execution.getStrategy()),
                outputs));
    }

    private List<EntityCommand> testSummary(BuildEvent event) {
        BuildEventId.TestSummaryId id = event.getId().getTestSummary();
        if (id.getLabel().isEmpty()) {
            return List.of();
        }
        TestSummary payload = event.getTestSummary();
        List<EntityCommand.TestLogRef> logs = new ArrayList<>();
        addSummaryLogs(logs, payload.getPassedList(), "PASSED");
        addSummaryLogs(logs, payload.getFailedList(), "FAILED");
        return List.of(new EntityCommand.TestSummarized(
                id.getLabel(),
                id.getConfiguration().getId(),
                TestOutcome.ofBazelStatus(payload.getOverallStatus().name()),
                positive(payload.getTotalRunCount()),
                positive(payload.getRunCount()),
                // Absent means "not sharded". A zero would put a phantom
                // zero-shard test into any histogram over this column.
                positive(payload.getShardCount()),
                positive(payload.getAttemptCount()),
                payload.getTotalNumCached(),
                ProtoTimes.micros(
                        payload.hasFirstStartTime(),
                        payload.getFirstStartTime(),
                        payload.getFirstStartTimeMillis()),
                ProtoTimes.micros(
                        payload.hasLastStopTime(),
                        payload.getLastStopTime(),
                        payload.getLastStopTimeMillis()),
                ProtoTimes.micros(
                        payload.hasTotalRunDuration(),
                        payload.getTotalRunDuration(),
                        payload.getTotalRunDurationMillis()),
                logs));
    }

    private static void addSummaryLogs(
            List<EntityCommand.TestLogRef> into, List<File> files, String status) {
        for (File file : files) {
            if (file.getUri().isEmpty()) {
                continue;
            }
            // A summary's Files carry a uri and nothing else -- no name, no
            // digest -- so the name stays absent rather than being invented
            // from the status.
            into.add(new EntityCommand.TestLogRef(
                    optional(file.getName()), file.getUri(), Optional.of(status)));
        }
    }

    // --- aborts and progress ----------------------------------------------

    /**
     * An {@code aborted} payload, whatever id it rides.
     *
     * <p>All four kinds are read, because which one carries an analysis failure
     * changed between Bazel 6.5.0 and 7.6.1: a consumer that looked only at
     * {@code targetCompleted} would find nothing on 7.6.1 and later, and one
     * that looked only at {@code targetConfigured} would find nothing on 6.5.0.
     */
    private List<EntityCommand> aborted(BuildEvent event) {
        BuildEventId id = event.getId();
        String kind;
        Optional<String> label;
        Optional<String> configuration;
        switch (id.getIdCase()) {
            case TARGET_COMPLETED -> {
                kind = "targetCompleted";
                label = optional(id.getTargetCompleted().getLabel());
                configuration = optional(id.getTargetCompleted().getConfiguration().getId());
            }
            case TARGET_CONFIGURED -> {
                kind = "targetConfigured";
                label = optional(id.getTargetConfigured().getLabel());
                configuration = Optional.empty();
            }
            case UNCONFIGURED_LABEL -> {
                kind = "unconfiguredLabel";
                label = optional(id.getUnconfiguredLabel().getLabel());
                configuration = Optional.empty();
            }
            case CONFIGURED_LABEL -> {
                kind = "configuredLabel";
                label = optional(id.getConfiguredLabel().getLabel());
                configuration = optional(id.getConfiguredLabel().getConfiguration().getId());
            }
            default -> {
                // Patterns and other ids abort too. They carry no label, and
                // the row exists so the count is right.
                kind = id.getIdCase().name().toLowerCase(Locale.ROOT);
                label = Optional.empty();
                configuration = Optional.empty();
            }
        }
        Aborted payload = event.getAborted();
        return List.of(new EntityCommand.TargetAborted(
                kind, label, configuration, reasonOf(payload), payload.getDescription()));
    }

    /**
     * The abort reason's name.
     *
     * <p>The enum's zero value is {@code UNKNOWN}, so a stream that gave no
     * reason produces the explicit unknown rather than a plausible-looking
     * guess. A value this build's protos do not carry is preserved as its
     * number instead of being flattened into {@code UNKNOWN}, which would
     * otherwise erase the difference between "Bazel said nothing" and "Bazel
     * said something newer than this build".
     */
    private static String reasonOf(Aborted payload) {
        Aborted.AbortReason reason = payload.getReason();
        return reason == Aborted.AbortReason.UNRECOGNIZED
                ? "UNRECOGNIZED_" + payload.getReasonValue()
                : reason.name();
    }

    private List<EntityCommand> progress(BuildEvent event) {
        var progress = event.getProgress();
        int stdout = progress.getStdoutBytes().size();
        int stderr = progress.getStderrBytes().size();
        if (stdout == 0 && stderr == 0) {
            return List.of();
        }
        return List.of(new EntityCommand.ProgressOutputSeen(stdout, stderr));
    }

    // --- small readings ---------------------------------------------------

    private static Optional<String> optional(String value) {
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static OptionalInt positive(int value) {
        return value <= 0 ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static int atLeastOne(int value) {
        return value < 1 ? 1 : value;
    }
}

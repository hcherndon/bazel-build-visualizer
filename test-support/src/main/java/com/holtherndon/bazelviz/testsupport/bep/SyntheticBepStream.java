package com.holtherndon.bazelviz.testsupport.bep;

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.ActionExecuted;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEvent;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildFinished;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildToolLogs;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Configuration;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.NamedSetOfFiles;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OptionsParsed;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.OutputGroup;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.PatternExpanded;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.Progress;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetComplete;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TargetConfigured;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestResult;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestSize;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestStatus;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestSummary;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.WorkspaceStatus;
import com.google.devtools.build.lib.runtime.proto.CommandLineOuterClass;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.holtherndon.bazelviz.testsupport.synthetic.SyntheticActionGenerator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A deterministic, structurally realistic Build Event Protocol stream built
 * from the real generated {@code build_event_stream.proto} classes.
 *
 * <h2>Why this exists</h2>
 *
 * Every Phase 1 parser (binary framing, JSON records, normalization,
 * parent/child linking) needs input that behaves like a real Bazel invocation.
 * Hand-rolled two-event fixtures pass tests that a real stream would fail, so
 * this generator produces the actual event shapes Bazel emits, in the order
 * Bazel emits them, with the announcement graph wired up.
 *
 * <h2>Announcement graph</h2>
 *
 * BEP is a tree: every event other than the root {@code started} event is
 * announced as a child of exactly one earlier event, and the stream is complete
 * when every announced child has arrived. This generator guarantees:
 *
 * <ul>
 *   <li>every event except index 0 is announced by a strictly earlier event;
 *   <li>each announced child id is byte-identical to the {@code id} of the
 *       event that later carries it;
 *   <li>no id is announced twice;
 *   <li>the final event carries {@code last_message = true}.
 * </ul>
 *
 * The one deliberate exception is opt-in: {@link Options#announceMissingTargetSummary()}
 * makes the test target's {@code target_configured} event announce a
 * {@code target_summary} child that never arrives, which is the case plan 17.11
 * and the {@code bep_announced_missing} table exist for. It is off by default so
 * that the default fixture satisfies "every announced child arrived".
 *
 * <h2>Layout</h2>
 *
 * The stream is prologue + N target units + one test unit + optional progress
 * padding + epilogue, so any requested event count {@code >= }
 * {@link #MIN_EVENT_COUNT} is produced <em>exactly</em>:
 *
 * <pre>
 *   index                       event
 *   0                           started                        (BuildStarted)
 *   1                           progress #0                    (Progress)
 *   2                           options_parsed                 (OptionsParsed)
 *   3                           structured_command_line "original"
 *   4                           structured_command_line "canonical"
 *   5                           workspace_status               (WorkspaceStatus)
 *   6                           configuration                  (Configuration)
 *   7                           pattern                        (PatternExpanded)
 *   8   + 5u + 0                target_configured  (unit u)    (TargetConfigured)
 *   8   + 5u + 1                progress #(1+u)                (Progress)
 *   8   + 5u + 2                named_set          (unit u)    (NamedSetOfFiles)
 *   8   + 5u + 3                action_completed   (unit u)    (ActionExecuted)
 *   8   + 5u + 4                target_completed   (unit u)    (TargetComplete)
 *   8+5T + 0                    target_configured  (test)      (TargetConfigured)
 *   8+5T + 1                    named_set          (test)      (NamedSetOfFiles)
 *   8+5T + 2                    target_completed   (test)      (TargetComplete)
 *   8+5T + 3                    test_result                    (TestResult)
 *   8+5T + 4                    test_summary                   (TestSummary)
 *   13+5T + p                   progress #(1+T+p)              (Progress)   [padding]
 *   13+5T+R + 0                 build_finished                 (BuildFinished)
 *   13+5T+R + 1                 build_metrics                  (BuildMetrics)
 *   13+5T+R + 2                 build_tool_logs                (BuildToolLogs), last_message
 * </pre>
 *
 * with {@code T = (eventCount - 16) / 5} and {@code R = (eventCount - 16) % 5}.
 *
 * <h2>Determinism</h2>
 *
 * {@link #eventAt(int)} is a pure function of {@code (seed, eventCount,
 * index)}: nothing is cached and nothing is materialized, so a 200,000-event
 * stream costs one message at a time. It is O(1) for every event except the
 * {@code pattern} event at index 7, which announces one child per configured
 * target and is therefore O(target count) in both time and message size — as
 * Bazel's own {@code pattern_expanded} event is. On a large stream that single
 * event is megabytes, which is deliberate: a parser that assumes every event is
 * small is wrong about real captures too.
 *
 * <p>Two runs with the same options produce
 * byte-identical serialized output. Map-typed protobuf fields are deliberately
 * left unset — protobuf map iteration order is unspecified, so setting one
 * would make the serialized bytes non-reproducible and break offset assertions.
 *
 * <p>The expected structure of a stream is available separately from
 * {@link BepFixtureCatalog}, which derives it from the layout rules rather than
 * from this generator, so a test asserting against the catalog is not merely
 * asking the generator to agree with itself.
 */
// Deprecated BEP fields (start_time_millis, overall_success, the *_millis
// duration pairs) are set on purpose: Bazel still emits them alongside their
// replacements, and a fixture that omitted them would let a parser that only
// reads the deprecated field — or only the new one — pass.
@SuppressWarnings("deprecation")
public final class SyntheticBepStream {

    /** Events emitted before the first target unit. */
    public static final int PROLOGUE_EVENTS = 8;

    /** Events emitted per target unit. */
    public static final int EVENTS_PER_TARGET_UNIT = 5;

    /** Events emitted for the single test target unit. */
    public static final int TEST_UNIT_EVENTS = 5;

    /** Events emitted after the last padding progress event. */
    public static final int EPILOGUE_EVENTS = 3;

    /** Smallest producible stream: prologue + test unit + epilogue, zero target units. */
    public static final int MIN_EVENT_COUNT = PROLOGUE_EVENTS + TEST_UNIT_EVENTS + EPILOGUE_EVENTS;

    /** 2026-01-01T00:00:00Z, in epoch microseconds. Fixed so timestamps are reproducible. */
    public static final long BASE_EPOCH_MICROS = 1_767_225_600_000_000L;

    /** Canonical seed, matching the benchmark seed convention in this module's README. */
    public static final long DEFAULT_SEED = 42L;

    /** The single configuration id every configured target in the stream refers to. */
    public static final String CONFIGURATION_ID = "6d1b2f0a7cba4e3c";

    /** Label of the one test target in the stream. */
    public static final String TEST_LABEL = "//src/test/java/com/example/core:core_test";

    /** Command-line label of the first {@code structured_command_line} event. */
    public static final String COMMAND_LINE_LABEL_ORIGINAL = "original";

    /** Command-line label of the second {@code structured_command_line} event. */
    public static final String COMMAND_LINE_LABEL_CANONICAL = "canonical";

    private static final String[] PACKAGE_PATHS = {
        "src/main/java/com/example/core",
        "src/main/java/com/example/net",
        "src/main/cc/engine",
        "src/main/go/server",
        "third_party/zlib",
        "tools/build_defs",
        "src/main/kotlin/com/example/ui",
    };

    private static final String[] TARGET_KINDS = {
        "java_library rule", "cc_library rule", "go_library rule",
        "genrule rule", "proto_library rule", "kt_jvm_library rule",
    };

    private static final String[] OUTPUT_SUFFIXES = {
        ".jar", ".a", ".o", ".so", ".zip", ".pb",
    };

    private static final String WORKSPACE_DIRECTORY = "/home/builder/workspace";

    private static final long INVOCATION_ID_SALT = 0x1A0C_4110L;
    private static final long BUILD_ID_SALT = 0x0B01_1D50L;

    /**
     * Generation parameters. {@code eventCount} is exact — the produced stream
     * contains precisely that many events.
     */
    public record Options(long seed, int eventCount, boolean announceMissingTargetSummary) {

        public Options {
            if (eventCount < MIN_EVENT_COUNT) {
                throw new IllegalArgumentException(
                        "eventCount must be at least " + MIN_EVENT_COUNT
                                + " (prologue " + PROLOGUE_EVENTS + " + test unit " + TEST_UNIT_EVENTS
                                + " + epilogue " + EPILOGUE_EVENTS + "), got " + eventCount);
            }
        }

        public static Options of(int eventCount) {
            return new Options(DEFAULT_SEED, eventCount, false);
        }

        public static Options of(long seed, int eventCount) {
            return new Options(seed, eventCount, false);
        }

        public Options withSeed(long newSeed) {
            return new Options(newSeed, eventCount, announceMissingTargetSummary);
        }

        public Options withEventCount(int newEventCount) {
            return new Options(seed, newEventCount, announceMissingTargetSummary);
        }

        public Options withAnnounceMissingTargetSummary(boolean announce) {
            return new Options(seed, eventCount, announce);
        }
    }

    private final Options options;
    private final int targetUnitCount;
    private final int paddingProgressCount;
    private final String invocationId;
    private final String buildId;

    public SyntheticBepStream(Options options) {
        this.options = options;
        int body = options.eventCount() - MIN_EVENT_COUNT;
        this.targetUnitCount = body / EVENTS_PER_TARGET_UNIT;
        this.paddingProgressCount = body % EVENTS_PER_TARGET_UNIT;
        this.invocationId = deterministicUuid(options.seed(), INVOCATION_ID_SALT).toString();
        this.buildId = deterministicUuid(options.seed(), BUILD_ID_SALT).toString();
    }

    public static SyntheticBepStream of(int eventCount) {
        return new SyntheticBepStream(Options.of(eventCount));
    }

    public static SyntheticBepStream of(long seed, int eventCount) {
        return new SyntheticBepStream(Options.of(seed, eventCount));
    }

    public Options options() {
        return options;
    }

    public int eventCount() {
        return options.eventCount();
    }

    public long seed() {
        return options.seed();
    }

    /** Number of full target units (each contributing {@value #EVENTS_PER_TARGET_UNIT} events). */
    public int targetUnitCount() {
        return targetUnitCount;
    }

    /** Progress events appended purely to reach an exact event count; 0..4. */
    public int paddingProgressCount() {
        return paddingProgressCount;
    }

    /** Total Progress-payload events: one in the prologue, one per target unit, plus padding. */
    public int progressEventCount() {
        return 1 + targetUnitCount + paddingProgressCount;
    }

    /** Index of the first target unit event. */
    public int targetUnitStartIndex() {
        return PROLOGUE_EVENTS;
    }

    /** Index of the test unit's first event. */
    public int testUnitStartIndex() {
        return PROLOGUE_EVENTS + EVENTS_PER_TARGET_UNIT * targetUnitCount;
    }

    /** Index of the first padding progress event (== epilogue start when there is no padding). */
    public int paddingStartIndex() {
        return testUnitStartIndex() + TEST_UNIT_EVENTS;
    }

    /** Index of the {@code build_finished} event. */
    public int epilogueStartIndex() {
        return paddingStartIndex() + paddingProgressCount;
    }

    /** Index of the single event carrying {@code last_message = true}. */
    public int lastMessageIndex() {
        return options.eventCount() - 1;
    }

    public String invocationId() {
        return invocationId;
    }

    public String buildId() {
        return buildId;
    }

    /** Label of target unit {@code unit}, {@code 0 <= unit < targetUnitCount()}. */
    public String targetLabel(int unit) {
        return "//" + PACKAGE_PATHS[Math.floorMod(unit, PACKAGE_PATHS.length)] + ":lib" + unit;
    }

    /** Opaque named-set id introduced by target unit {@code unit}. */
    public String namedSetId(int unit) {
        return String.format("%016x", hash(0x5E7, unit));
    }

    /** Opaque named-set id introduced by the test unit. */
    public String testNamedSetId() {
        return String.format("%016x", hash(0x5E7, -1));
    }

    /** Primary output path of target unit {@code unit}'s reported action. */
    public String primaryOutputPath(int unit) {
        String pkg = PACKAGE_PATHS[Math.floorMod(unit, PACKAGE_PATHS.length)];
        String suffix = OUTPUT_SUFFIXES[Math.floorMod((int) hash(0x0F5, unit), OUTPUT_SUFFIXES.length)];
        return "bazel-out/k8-fastbuild/bin/" + pkg + "/lib" + unit + suffix;
    }

    /** Lazily generated view of the whole stream. Nothing is retained between elements. */
    public Stream<BuildEvent> events() {
        return IntStream.range(0, options.eventCount()).mapToObj(this::eventAt);
    }

    /** Lazily generated iterator over the whole stream. */
    public Iterator<BuildEvent> iterator() {
        return new Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < options.eventCount();
            }

            @Override
            public BuildEvent next() {
                if (next >= options.eventCount()) {
                    throw new NoSuchElementException();
                }
                return eventAt(next++);
            }
        };
    }

    /**
     * Builds the event at {@code index}. Pure function of {@code (seed,
     * eventCount, index)} — calling it twice returns equal messages. Cost is
     * independent of {@code index} except at index 7, the {@code pattern} event,
     * which announces every configured target.
     */
    public BuildEvent eventAt(int index) {
        if (index < 0 || index >= options.eventCount()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " outside [0, " + options.eventCount() + ")");
        }
        if (index < PROLOGUE_EVENTS) {
            return prologueEvent(index);
        }
        int testStart = testUnitStartIndex();
        if (index < testStart) {
            int offset = index - PROLOGUE_EVENTS;
            return targetUnitEvent(offset / EVENTS_PER_TARGET_UNIT, offset % EVENTS_PER_TARGET_UNIT);
        }
        int paddingStart = paddingStartIndex();
        if (index < paddingStart) {
            return testUnitEvent(index - testStart);
        }
        int epilogueStart = epilogueStartIndex();
        if (index < epilogueStart) {
            int p = index - paddingStart;
            return progressEvent(1 + targetUnitCount + p);
        }
        return epilogueEvent(index - epilogueStart);
    }

    // ---------------------------------------------------------------- prologue

    private BuildEvent prologueEvent(int index) {
        return switch (index) {
            case 0 -> startedEvent();
            case 1 -> progressEvent(0);
            case 2 -> optionsParsedEvent();
            case 3 -> structuredCommandLineEvent(COMMAND_LINE_LABEL_ORIGINAL);
            case 4 -> structuredCommandLineEvent(COMMAND_LINE_LABEL_CANONICAL);
            case 5 -> workspaceStatusEvent();
            case 6 -> configurationEvent();
            case 7 -> patternEvent();
            default -> throw new IllegalStateException("unreachable prologue index " + index);
        };
    }

    private BuildEvent startedEvent() {
        BuildEvent.Builder b = BuildEvent.newBuilder().setId(startedId());
        b.addChildren(progressId(0));
        b.addChildren(optionsParsedId());
        b.addChildren(structuredCommandLineId(COMMAND_LINE_LABEL_ORIGINAL));
        b.addChildren(structuredCommandLineId(COMMAND_LINE_LABEL_CANONICAL));
        b.addChildren(workspaceStatusId());
        b.addChildren(configurationId());
        b.addChildren(patternId());
        b.addChildren(buildFinishedId());
        b.addChildren(buildMetricsId());
        b.setStarted(com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildStarted
                .newBuilder()
                .setUuid(invocationId)
                .setStartTimeMillis(BASE_EPOCH_MICROS / 1_000L)
                .setStartTime(timestamp(BASE_EPOCH_MICROS))
                .setBuildToolVersion("8.4.2")
                .setOptionsDescription("--config=ci --remote_cache=grpcs://cache.example.internal")
                .setCommand("build")
                .setWorkingDirectory(WORKSPACE_DIRECTORY)
                .setWorkspaceDirectory(WORKSPACE_DIRECTORY)
                .setServerPid(41827L)
                .setHost("builder-07")
                .setUser("builder"));
        return b.build();
    }

    private BuildEvent progressEvent(int opaqueCount) {
        BuildEvent.Builder b = BuildEvent.newBuilder().setId(progressId(opaqueCount));
        if (opaqueCount < progressEventCount() - 1) {
            b.addChildren(progressId(opaqueCount + 1));
        }
        long h = hash(0x9401, opaqueCount);
        int done = Math.floorMod((int) h, 4096);
        int total = 4096 + Math.floorMod((int) (h >>> 20), 512);
        b.setProgress(Progress.newBuilder()
                .setStdout("[" + done + " / " + total + "] "
                        + SyntheticActionGenerator.MNEMONICS[
                                Math.floorMod((int) (h >>> 33), SyntheticActionGenerator.MNEMONICS.length)]
                        + " " + PACKAGE_PATHS[Math.floorMod(opaqueCount, PACKAGE_PATHS.length)] + "\n")
                .setStderr(opaqueCount % 7 == 3
                        ? "INFO: Elapsed time: " + (opaqueCount * 37 % 900) + "s\n"
                        : ""));
        return b.build();
    }

    private BuildEvent optionsParsedEvent() {
        return BuildEvent.newBuilder()
                .setId(optionsParsedId())
                .setOptionsParsed(OptionsParsed.newBuilder()
                        .addStartupOptions("--output_base=/home/builder/.cache/bazel/_bazel_builder/9f2c")
                        .addStartupOptions("--max_idle_secs=10800")
                        .addExplicitStartupOptions("--max_idle_secs=10800")
                        .addCmdLine("--config=ci")
                        .addCmdLine("--remote_cache=grpcs://cache.example.internal")
                        .addCmdLine("--build_event_binary_file=/tmp/bep.bin")
                        .addCmdLine("--keep_going")
                        .addExplicitCmdLine("--config=ci")
                        .addExplicitCmdLine("--build_event_binary_file=/tmp/bep.bin")
                        .setToolTag("bazel"))
                .build();
    }

    private BuildEvent structuredCommandLineEvent(String label) {
        CommandLineOuterClass.CommandLine.Builder cl =
                CommandLineOuterClass.CommandLine.newBuilder().setCommandLineLabel(label);
        cl.addSections(CommandLineOuterClass.CommandLineSection.newBuilder()
                .setSectionLabel("executable")
                .setChunkList(CommandLineOuterClass.ChunkList.newBuilder().addChunk("bazel")));
        cl.addSections(CommandLineOuterClass.CommandLineSection.newBuilder()
                .setSectionLabel("startup options")
                .setOptionList(CommandLineOuterClass.OptionList.newBuilder()
                        .addOption(CommandLineOuterClass.Option.newBuilder()
                                .setCombinedForm("--max_idle_secs=10800")
                                .setOptionName("max_idle_secs")
                                .setOptionValue("10800"))));
        cl.addSections(CommandLineOuterClass.CommandLineSection.newBuilder()
                .setSectionLabel("command")
                .setChunkList(CommandLineOuterClass.ChunkList.newBuilder().addChunk("build")));
        cl.addSections(CommandLineOuterClass.CommandLineSection.newBuilder()
                .setSectionLabel("command options")
                .setOptionList(CommandLineOuterClass.OptionList.newBuilder()
                        .addOption(CommandLineOuterClass.Option.newBuilder()
                                .setCombinedForm("--config=ci")
                                .setOptionName("config")
                                .setOptionValue("ci"))
                        .addOption(CommandLineOuterClass.Option.newBuilder()
                                .setCombinedForm("--keep_going")
                                .setOptionName("keep_going")
                                .setOptionValue("1"))));
        cl.addSections(CommandLineOuterClass.CommandLineSection.newBuilder()
                .setSectionLabel("residual")
                .setChunkList(CommandLineOuterClass.ChunkList.newBuilder().addChunk("//...")));
        return BuildEvent.newBuilder()
                .setId(structuredCommandLineId(label))
                .setStructuredCommandLine(cl)
                .build();
    }

    private BuildEvent workspaceStatusEvent() {
        return BuildEvent.newBuilder()
                .setId(workspaceStatusId())
                .setWorkspaceStatus(WorkspaceStatus.newBuilder()
                        .addItem(WorkspaceStatus.Item.newBuilder()
                                .setKey("BUILD_TIMESTAMP")
                                .setValue(Long.toString(BASE_EPOCH_MICROS / 1_000_000L)))
                        .addItem(WorkspaceStatus.Item.newBuilder()
                                .setKey("BUILD_HOST").setValue("builder-07"))
                        .addItem(WorkspaceStatus.Item.newBuilder()
                                .setKey("BUILD_USER").setValue("builder"))
                        .addItem(WorkspaceStatus.Item.newBuilder()
                                .setKey("STABLE_GIT_COMMIT")
                                .setValue(String.format("%016x", hash(0x617, 0)))))
                .build();
    }

    private BuildEvent configurationEvent() {
        // make_variable is a protobuf map and is deliberately left unset: map
        // iteration order is unspecified, so populating it would make the
        // serialized bytes non-reproducible.
        return BuildEvent.newBuilder()
                .setId(configurationId())
                .setConfiguration(Configuration.newBuilder()
                        .setMnemonic("k8-fastbuild")
                        .setPlatformName("k8")
                        .setCpu("k8")
                        .setIsTool(false))
                .build();
    }

    private BuildEvent patternEvent() {
        BuildEvent.Builder b = BuildEvent.newBuilder().setId(patternId());
        for (int u = 0; u < targetUnitCount; u++) {
            b.addChildren(targetConfiguredId(targetLabel(u)));
        }
        b.addChildren(targetConfiguredId(TEST_LABEL));
        b.setExpanded(PatternExpanded.newBuilder()
                .addTestSuiteExpansions(PatternExpanded.TestSuiteExpansion.newBuilder()
                        .setSuiteLabel("//src/test/java/com/example/core:all_tests")
                        .addTestLabels(TEST_LABEL)));
        return b.build();
    }

    // ------------------------------------------------------------ target units

    private BuildEvent targetUnitEvent(int unit, int slot) {
        return switch (slot) {
            case 0 -> targetConfiguredEvent(unit);
            case 1 -> progressEvent(1 + unit);
            case 2 -> namedSetEvent(unit);
            case 3 -> actionExecutedEvent(unit);
            case 4 -> targetCompleteEvent(unit);
            default -> throw new IllegalStateException("unreachable target-unit slot " + slot);
        };
    }

    private BuildEvent targetConfiguredEvent(int unit) {
        String label = targetLabel(unit);
        return BuildEvent.newBuilder()
                .setId(targetConfiguredId(label))
                .addChildren(namedSetIdOf(namedSetId(unit)))
                .addChildren(actionCompletedId(primaryOutputPath(unit), label))
                .addChildren(targetCompletedId(label))
                .setConfigured(TargetConfigured.newBuilder()
                        .setTargetKind(TARGET_KINDS[Math.floorMod((int) hash(0x71D, unit), TARGET_KINDS.length)])
                        .addTag(unit % 3 == 0 ? "no-remote-cache" : "manual"))
                .build();
    }

    private BuildEvent namedSetEvent(int unit) {
        NamedSetOfFiles.Builder set = NamedSetOfFiles.newBuilder();
        int fileCount = 1 + Math.floorMod((int) hash(0xF11E, unit), 4);
        for (int f = 0; f < fileCount; f++) {
            set.addFiles(file(unit, f));
        }
        if (unit > 0) {
            // Depsets nest: refer to the previous unit's set.
            set.addFileSets(BuildEventId.NamedSetOfFilesId.newBuilder().setId(namedSetId(unit - 1)));
        }
        return BuildEvent.newBuilder()
                .setId(namedSetIdOf(namedSetId(unit)))
                .setNamedSetOfFiles(set)
                .build();
    }

    private BuildEvent actionExecutedEvent(int unit) {
        String label = targetLabel(unit);
        String output = primaryOutputPath(unit);
        long h = hash(0xAC7, unit);
        String mnemonic = SyntheticActionGenerator.MNEMONICS[
                Math.floorMod((int) h, SyntheticActionGenerator.MNEMONICS.length)];
        long startMicros = BASE_EPOCH_MICROS + 2_000_000L + unit * 31_000L;
        long durationMicros = 400L + Math.floorMod(h >>> 17, 4_000_000L);
        return BuildEvent.newBuilder()
                .setId(actionCompletedId(output, label))
                .setAction(ActionExecuted.newBuilder()
                        .setSuccess(true)
                        .setType(mnemonic)
                        .setExitCode(0)
                        .setPrimaryOutput(com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File
                                .newBuilder()
                                .setName(output.substring(output.lastIndexOf('/') + 1))
                                .addPathPrefix("bazel-out")
                                .addPathPrefix("k8-fastbuild")
                                .addPathPrefix("bin")
                                .setUri("file://" + WORKSPACE_DIRECTORY + "/" + output)
                                .setDigest(String.format("%016x%016x", hash(0xD16, unit), hash(0xD17, unit)))
                                .setLength(1024L + Math.floorMod(hash(0x1E4, unit), 8_000_000L)))
                        .addCommandLine("/usr/bin/" + mnemonic.toLowerCase(java.util.Locale.ROOT))
                        .addCommandLine("-c")
                        .addCommandLine(PACKAGE_PATHS[Math.floorMod(unit, PACKAGE_PATHS.length)] + "/src" + unit)
                        .addCommandLine("-o")
                        .addCommandLine(output)
                        .setStartTime(timestamp(startMicros))
                        .setEndTime(timestamp(startMicros + durationMicros)))
                .build();
    }

    private BuildEvent targetCompleteEvent(int unit) {
        return BuildEvent.newBuilder()
                .setId(targetCompletedId(targetLabel(unit)))
                .setCompleted(TargetComplete.newBuilder()
                        .setSuccess(true)
                        .addOutputGroup(OutputGroup.newBuilder()
                                .setName("default")
                                .setIncomplete(false)
                                .addFileSets(BuildEventId.NamedSetOfFilesId.newBuilder()
                                        .setId(namedSetId(unit))))
                        .addTag(unit % 3 == 0 ? "no-remote-cache" : "manual"))
                .build();
    }

    private com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File file(int unit, int fileIndex) {
        long h = hash(0xF11E, unit * 31L + fileIndex);
        String pkg = PACKAGE_PATHS[Math.floorMod(unit, PACKAGE_PATHS.length)];
        String name = "lib" + unit + "_" + fileIndex
                + OUTPUT_SUFFIXES[Math.floorMod((int) h, OUTPUT_SUFFIXES.length)];
        return com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File.newBuilder()
                .setName(name)
                .addPathPrefix("bazel-out")
                .addPathPrefix("k8-fastbuild")
                .addPathPrefix("bin")
                .setUri("file://" + WORKSPACE_DIRECTORY + "/bazel-out/k8-fastbuild/bin/" + pkg + "/" + name)
                .setDigest(String.format("%016x%016x", h, hash(0xF11F, unit * 31L + fileIndex)))
                .setLength(64L + Math.floorMod(h >>> 11, 4_000_000L))
                .build();
    }

    // -------------------------------------------------------------- test unit

    private BuildEvent testUnitEvent(int slot) {
        return switch (slot) {
            case 0 -> testTargetConfiguredEvent();
            case 1 -> testNamedSetEvent();
            case 2 -> testTargetCompleteEvent();
            case 3 -> testResultEvent();
            case 4 -> testSummaryEvent();
            default -> throw new IllegalStateException("unreachable test-unit slot " + slot);
        };
    }

    private BuildEvent testTargetConfiguredEvent() {
        BuildEvent.Builder b = BuildEvent.newBuilder()
                .setId(targetConfiguredId(TEST_LABEL))
                .addChildren(namedSetIdOf(testNamedSetId()))
                .addChildren(targetCompletedId(TEST_LABEL))
                .addChildren(testResultId(TEST_LABEL, 1, 1, 1))
                .addChildren(testSummaryId(TEST_LABEL));
        if (options.announceMissingTargetSummary()) {
            // Deliberately announced and never emitted: plan 17.11 / bep_announced_missing.
            b.addChildren(targetSummaryId(TEST_LABEL));
        }
        return b.setConfigured(TargetConfigured.newBuilder()
                        .setTargetKind("java_test rule")
                        .setTestSize(TestSize.MEDIUM)
                        .addTag("flaky"))
                .build();
    }

    private BuildEvent testNamedSetEvent() {
        return BuildEvent.newBuilder()
                .setId(namedSetIdOf(testNamedSetId()))
                .setNamedSetOfFiles(NamedSetOfFiles.newBuilder()
                        .addFiles(com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File
                                .newBuilder()
                                .setName("core_test.jar")
                                .addPathPrefix("bazel-out")
                                .addPathPrefix("k8-fastbuild")
                                .addPathPrefix("bin")
                                .setUri("file://" + WORKSPACE_DIRECTORY
                                        + "/bazel-out/k8-fastbuild/bin/src/test/java/com/example/core/core_test.jar")
                                .setDigest(String.format("%016x%016x", hash(0x7E57, 0), hash(0x7E57, 1)))
                                .setLength(184_320L)))
                .build();
    }

    private BuildEvent testTargetCompleteEvent() {
        return BuildEvent.newBuilder()
                .setId(targetCompletedId(TEST_LABEL))
                .setCompleted(TargetComplete.newBuilder()
                        .setSuccess(true)
                        .setTestTimeout(Duration.newBuilder().setSeconds(300))
                        .addOutputGroup(OutputGroup.newBuilder()
                                .setName("default")
                                .addFileSets(BuildEventId.NamedSetOfFilesId.newBuilder()
                                        .setId(testNamedSetId())))
                        .addTag("flaky"))
                .build();
    }

    private BuildEvent testResultEvent() {
        long attemptStart = BASE_EPOCH_MICROS + 9_000_000L;
        long attemptDuration = 1_250_000L + Math.floorMod(hash(0x7357, 0), 3_000_000L);
        return BuildEvent.newBuilder()
                .setId(testResultId(TEST_LABEL, 1, 1, 1))
                .setTestResult(TestResult.newBuilder()
                        .setStatus(TestStatus.PASSED)
                        .setStatusDetails("")
                        .setCachedLocally(false)
                        .setTestAttemptStartMillisEpoch(attemptStart / 1_000L)
                        .setTestAttemptStart(timestamp(attemptStart))
                        .setTestAttemptDurationMillis(attemptDuration / 1_000L)
                        .setTestAttemptDuration(duration(attemptDuration))
                        .addTestActionOutput(testOutputFile("test.log"))
                        .addTestActionOutput(testOutputFile("test.xml"))
                        .setExecutionInfo(TestResult.ExecutionInfo.newBuilder()
                                .setStrategy("remote")
                                .setCachedRemotely(false)
                                .setExitCode(0)
                                .setHostname("executor-14.example.internal")))
                .build();
    }

    private com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File testOutputFile(String name) {
        return com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File.newBuilder()
                .setName(name)
                .setUri("file://" + WORKSPACE_DIRECTORY
                        + "/bazel-out/k8-fastbuild/testlogs/src/test/java/com/example/core/core_test/" + name)
                .setDigest(String.format("%016x", hash(0x7357, name.hashCode())))
                .setLength(2048L + Math.floorMod(hash(0x7358, name.hashCode()), 65_536L))
                .build();
    }

    private BuildEvent testSummaryEvent() {
        long firstStart = BASE_EPOCH_MICROS + 9_000_000L;
        long totalRun = 1_250_000L + Math.floorMod(hash(0x7357, 0), 3_000_000L);
        return BuildEvent.newBuilder()
                .setId(testSummaryId(TEST_LABEL))
                .setTestSummary(TestSummary.newBuilder()
                        .setOverallStatus(TestStatus.PASSED)
                        .setTotalRunCount(1)
                        .setRunCount(1)
                        .setAttemptCount(1)
                        .setShardCount(1)
                        .addPassed(testOutputFile("test.log"))
                        .setTotalNumCached(0)
                        .setFirstStartTimeMillis(firstStart / 1_000L)
                        .setFirstStartTime(timestamp(firstStart))
                        .setLastStopTimeMillis((firstStart + totalRun) / 1_000L)
                        .setLastStopTime(timestamp(firstStart + totalRun))
                        .setTotalRunDurationMillis(totalRun / 1_000L)
                        .setTotalRunDuration(duration(totalRun)))
                .build();
    }

    // --------------------------------------------------------------- epilogue

    private BuildEvent epilogueEvent(int slot) {
        return switch (slot) {
            case 0 -> buildFinishedEvent();
            case 1 -> buildMetricsEvent();
            case 2 -> buildToolLogsEvent();
            default -> throw new IllegalStateException("unreachable epilogue slot " + slot);
        };
    }

    private BuildEvent buildFinishedEvent() {
        long finishMicros = BASE_EPOCH_MICROS + 15_000_000L + targetUnitCount * 31_000L;
        return BuildEvent.newBuilder()
                .setId(buildFinishedId())
                .addChildren(buildToolLogsId())
                .setFinished(BuildFinished.newBuilder()
                        .setOverallSuccess(true)
                        .setExitCode(BuildFinished.ExitCode.newBuilder().setName("SUCCESS").setCode(0))
                        .setFinishTimeMillis(finishMicros / 1_000L)
                        .setFinishTime(timestamp(finishMicros)))
                .build();
    }

    private BuildEvent buildMetricsEvent() {
        int actionsExecuted = targetUnitCount + 1;
        return BuildEvent.newBuilder()
                .setId(buildMetricsId())
                .setBuildMetrics(BuildMetrics.newBuilder()
                        .setActionSummary(BuildMetrics.ActionSummary.newBuilder()
                                .setActionsCreated(actionsExecuted * 4L)
                                .setActionsCreatedNotIncludingAspects(actionsExecuted * 3L)
                                .setActionsExecuted(actionsExecuted)
                                .addActionData(BuildMetrics.ActionSummary.ActionData.newBuilder()
                                        .setMnemonic("Javac")
                                        .setActionsExecuted(Math.max(1, actionsExecuted / 2))
                                        .setActionsCreated(Math.max(1, actionsExecuted / 2))
                                        .setFirstStartedMs(BASE_EPOCH_MICROS / 1_000L + 2_000)
                                        .setLastEndedMs(BASE_EPOCH_MICROS / 1_000L + 12_000))
                                .addRunnerCount(BuildMetrics.ActionSummary.RunnerCount.newBuilder()
                                        .setName("remote")
                                        .setCount(actionsExecuted)
                                        .setExecKind("remote")))
                        .setMemoryMetrics(BuildMetrics.MemoryMetrics.newBuilder()
                                .setUsedHeapSizePostBuild(734_003_200L)
                                .setPeakPostGcHeapSize(1_073_741_824L))
                        .setTargetMetrics(BuildMetrics.TargetMetrics.newBuilder()
                                .setTargetsConfigured(targetUnitCount + 1L)
                                .setTargetsConfiguredNotIncludingAspects(targetUnitCount + 1L))
                        .setPackageMetrics(BuildMetrics.PackageMetrics.newBuilder()
                                .setPackagesLoaded(Math.min(PACKAGE_PATHS.length, targetUnitCount + 1)))
                        .setTimingMetrics(BuildMetrics.TimingMetrics.newBuilder()
                                .setCpuTimeInMs(8_400L + targetUnitCount * 7L)
                                .setWallTimeInMs(15_000L + targetUnitCount * 31L)
                                .setAnalysisPhaseTimeInMs(2_100L)
                                .setExecutionPhaseTimeInMs(12_400L)
                                .setActionsExecutionStartInMs(2_600L))
                        .setCumulativeMetrics(BuildMetrics.CumulativeMetrics.newBuilder()
                                .setNumAnalyses(3)
                                .setNumBuilds(2)))
                .build();
    }

    private BuildEvent buildToolLogsEvent() {
        return BuildEvent.newBuilder()
                .setId(buildToolLogsId())
                .setLastMessage(true)
                .setBuildToolLogs(BuildToolLogs.newBuilder()
                        .addLog(com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File
                                .newBuilder()
                                .setName("command.profile.gz")
                                .setUri("file:///home/builder/.cache/bazel/_bazel_builder/9f2c/command.profile.gz"))
                        .addLog(com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.File
                                .newBuilder()
                                .setName("elapsed time")
                                .setContents(com.google.protobuf.ByteString.copyFromUtf8("15.4"))))
                .build();
    }

    // ---------------------------------------------------------------- event ids

    public BuildEventId startedId() {
        return BuildEventId.newBuilder()
                .setStarted(BuildEventId.BuildStartedId.getDefaultInstance())
                .build();
    }

    public BuildEventId progressId(int opaqueCount) {
        return BuildEventId.newBuilder()
                .setProgress(BuildEventId.ProgressId.newBuilder().setOpaqueCount(opaqueCount))
                .build();
    }

    public BuildEventId optionsParsedId() {
        return BuildEventId.newBuilder()
                .setOptionsParsed(BuildEventId.OptionsParsedId.getDefaultInstance())
                .build();
    }

    public BuildEventId structuredCommandLineId(String label) {
        return BuildEventId.newBuilder()
                .setStructuredCommandLine(
                        BuildEventId.StructuredCommandLineId.newBuilder().setCommandLineLabel(label))
                .build();
    }

    public BuildEventId workspaceStatusId() {
        return BuildEventId.newBuilder()
                .setWorkspaceStatus(BuildEventId.WorkspaceStatusId.getDefaultInstance())
                .build();
    }

    public BuildEventId configurationId() {
        return BuildEventId.newBuilder()
                .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID))
                .build();
    }

    public BuildEventId patternId() {
        return BuildEventId.newBuilder()
                .setPattern(BuildEventId.PatternExpandedId.newBuilder().addPattern("//..."))
                .build();
    }

    public BuildEventId targetConfiguredId(String label) {
        return BuildEventId.newBuilder()
                .setTargetConfigured(BuildEventId.TargetConfiguredId.newBuilder().setLabel(label))
                .build();
    }

    public BuildEventId namedSetIdOf(String setId) {
        return BuildEventId.newBuilder()
                .setNamedSet(BuildEventId.NamedSetOfFilesId.newBuilder().setId(setId))
                .build();
    }

    public BuildEventId actionCompletedId(String primaryOutput, String label) {
        return BuildEventId.newBuilder()
                .setActionCompleted(BuildEventId.ActionCompletedId.newBuilder()
                        .setPrimaryOutput(primaryOutput)
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID)))
                .build();
    }

    public BuildEventId targetCompletedId(String label) {
        return BuildEventId.newBuilder()
                .setTargetCompleted(BuildEventId.TargetCompletedId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID)))
                .build();
    }

    public BuildEventId testResultId(String label, int run, int shard, int attempt) {
        return BuildEventId.newBuilder()
                .setTestResult(BuildEventId.TestResultId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID))
                        .setRun(run)
                        .setShard(shard)
                        .setAttempt(attempt))
                .build();
    }

    public BuildEventId testSummaryId(String label) {
        return BuildEventId.newBuilder()
                .setTestSummary(BuildEventId.TestSummaryId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID)))
                .build();
    }

    public BuildEventId targetSummaryId(String label) {
        return BuildEventId.newBuilder()
                .setTargetSummary(BuildEventId.TargetSummaryId.newBuilder()
                        .setLabel(label)
                        .setConfiguration(BuildEventId.ConfigurationId.newBuilder().setId(CONFIGURATION_ID)))
                .build();
    }

    public BuildEventId buildFinishedId() {
        return BuildEventId.newBuilder()
                .setBuildFinished(BuildEventId.BuildFinishedId.getDefaultInstance())
                .build();
    }

    public BuildEventId buildMetricsId() {
        return BuildEventId.newBuilder()
                .setBuildMetrics(BuildEventId.BuildMetricsId.getDefaultInstance())
                .build();
    }

    public BuildEventId buildToolLogsId() {
        return BuildEventId.newBuilder()
                .setBuildToolLogs(BuildEventId.BuildToolLogsId.getDefaultInstance())
                .build();
    }

    // ------------------------------------------------------------------ util

    private static Timestamp timestamp(long epochMicros) {
        return Timestamp.newBuilder()
                .setSeconds(Math.floorDiv(epochMicros, 1_000_000L))
                .setNanos((int) Math.floorMod(epochMicros, 1_000_000L) * 1_000)
                .build();
    }

    private static Duration duration(long micros) {
        return Duration.newBuilder()
                .setSeconds(micros / 1_000_000L)
                .setNanos((int) (micros % 1_000_000L) * 1_000)
                .build();
    }

    private long hash(int salt, long index) {
        return mix(mix(options.seed() + salt * 0x9E3779B97F4A7C15L) + index);
    }

    private static UUID deterministicUuid(long seed, long salt) {
        long hi = mix(seed + salt);
        long lo = mix(hi ^ salt);
        return new UUID(hi, lo);
    }

    /** SplitMix64 finalizer; the module's determinism contract depends on it staying fixed. */
    private static long mix(long input) {
        long z = input + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

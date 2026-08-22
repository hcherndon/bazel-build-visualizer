package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a user's command into the command that will run, and explains every
 * difference (plan 7.1, rendered by plan 4.3, governed by ADR-007).
 *
 * <h2>Where flags go</h2>
 *
 * <p>Injected flags are appended to the command options, before any target
 * pattern and before any user-supplied {@code --}. Two facts make that the only
 * safe placement, and both were established by running real binaries:
 *
 * <ul>
 *   <li>The last occurrence of a single-valued flag wins, silently. Appending
 *       therefore overrides a user's earlier value without needing to find and
 *       remove it — and because Bazel says nothing about the flag it shadowed,
 *       this application has to be the one that tells the user.
 *   <li>After a {@code --}, everything is a target pattern. A flag appended
 *       past one is not rejected: its leading dash is read as the negative-
 *       pattern marker, the build fails during target resolution, and
 *       <em>no event stream is produced at all</em>. A capture that appended
 *       blindly would produce dead invocations with no obvious cause.
 * </ul>
 *
 * <p>{@link BazelCommand#toArgv()} places command options before targets and
 * before the separator, so honouring this is a matter of adding to the right
 * list rather than a rule to remember.
 *
 * <h2>Nothing is injected on a maybe</h2>
 *
 * <p>A capability is injected only when the binary was observed to support it.
 * {@link CapabilityStatus#UNKNOWN} — a probe that failed — is treated exactly
 * like unsupported for the purpose of injection, and differently for the
 * purpose of explanation: the plan says "we could not ask", not "your Bazel
 * cannot do this".
 */
public final class InstrumentationPlanner {

    /** File name for the BEP fallback, under the session's {@code raw/}. */
    public static final String FALLBACK_BEP_FILE = "bep-fallback.bin";

    /** Where a captured compact execution log is written. */
    public static final String EXECUTION_LOG_FILE = "execution-log.zst";

    /** Where a captured pre-compact execution log is written. */
    public static final String EXECUTION_LOG_BINARY_FILE = "execution-log.bin";

    /** Where a captured trace profile is written. */
    public static final String PROFILE_FILE = "profile.json";

    /**
     * The upload timeout injected alongside the local backend.
     *
     * <p>Bazel's default {@code --bes_timeout} is {@code 0s}, which means no
     * timeout at all. That is a reasonable default for a real backend and a
     * dangerous one for this application: measured, a BES server that stops
     * acknowledging makes Bazel wait <em>indefinitely</em> at the end of an
     * otherwise successful build — no error, no retry, no give-up, and the
     * workspace lock held the whole time. A bug in this application would then
     * wedge the user's workspace until they found and killed a Bazel server.
     *
     * <p>Sixty seconds converts every such failure into a bounded, single-line
     * error and a normal exit. It is far longer than a healthy capture ever
     * needs — acknowledgement follows a journal append — so it costs nothing
     * when things work.
     */
    public static final String BES_TIMEOUT_VALUE = "60s";

    public InstrumentationPlan plan(PlanRequest request) {
        Objects.requireNonNull(request, "request");
        BazelCommand original = request.original();
        BazelCapabilities capabilities = request.capabilities();

        List<AddedFlag> added = new ArrayList<>();
        List<ReplacedFlag> replaced = new ArrayList<>();
        List<PlanConflict> conflicts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Path> outputs = new ArrayList<>();
        Map<DataSource, SourceAvailability.Entry> availability = new EnumMap<>(DataSource.class);

        if (original.isEmpty()) {
            errors.add("no Bazel command was given, so there is nothing to instrument");
        } else if (!isInstrumentable(original.command(), capabilities)) {
            conflicts.add(new PlanConflict(
                    PlanConflict.Kind.COMMAND_NOT_INSTRUMENTABLE,
                    true,
                    "'" + original.command() + "' does not publish a build event stream",
                    "The selected Bazel does not accept --bes_backend for '" + original.command()
                            + "', so there is nothing for this application to capture. Commands like"
                            + " build, test and run do publish one.",
                    List.of(new PlanConflict.Resolution(
                            PlanConflict.RESOLUTION_CANCEL,
                            "Cancel and edit the command",
                            "Nothing is captured until the command is one that produces events.")),
                    Optional.of(original.command())));
        }

        UserFlags userFlags = UserFlags.of(original, request.effectiveOptions());
        if (request.effectiveOptions().isEmpty()) {
            // Said out loud. Without the expansion this planner can only see
            // what the user typed, and an option set in a .bazelrc -- their
            // team's BES backend, say -- is invisible to every conflict check
            // below. Injecting over one silently is the failure ADR-007 exists
            // to prevent, so the inability to check is reported rather than
            // quietly treated as "nothing was set".
            warnings.add("Bazel's own option expansion could not be read, so options set in"
                    + " .bazelrc files were not inspected. If one of them sets --bes_backend,"
                    + " this build's results will go here instead, without being reported as a"
                    + " conflict.");
        } else if (!"build".equals(original.command()) && !original.isEmpty()) {
            // The expansion reports the common and build sections. A line under
            // a section this command does not inherit -- a test-only one, say --
            // is not visible, and claiming to have checked would be worse than
            // saying which part was checked.
            warnings.add("Options in .bazelrc's 'common' and 'build' sections were inspected."
                    + " A '" + original.command() + "'-specific section was not, so an option set"
                    + " only there is not reflected in this plan.");
        }

        // --- conflict: the user already names a BES backend (plan 8.5) -------
        boolean useFileFallback = false;
        if (userFlags.has("bes_backend")) {
            String chosen = request.resolutionFor(PlanConflict.Kind.EXISTING_BES_BACKEND).orElse(null);
            if (chosen == null) {
                conflicts.add(existingBesBackendConflict(
                        userFlags.raw("bes_backend"), userFlags.originOf("bes_backend")));
            } else if (PlanConflict.RESOLUTION_KEEP_BES_USE_FILE.equals(chosen)) {
                useFileFallback = true;
            } else if (PlanConflict.RESOLUTION_CANCEL.equals(chosen)) {
                errors.add("the launch was cancelled so the command could be edited");
            }
            // RESOLUTION_REPLACE_BES falls through: the injected backend is
            // appended and, by last-wins, supersedes the user's.
        }

        // --- the embedded backend, or the file fallback ----------------------
        if (useFileFallback) {
            addBepFileFallback(request, capabilities, added, outputs, availability, warnings,
                    conflicts, replaced, userFlags);
        } else if (request.besEndpoint().isEmpty()) {
            errors.add("the embedded Build Event Service is not listening, so no events could be"
                    + " captured from this build");
            availability.put(DataSource.BEP, new SourceAvailability.Entry(
                    SourceAvailability.Availability.UNAVAILABLE,
                    "the embedded BES server did not start",
                    Optional.empty()));
        } else {
            addBesBackend(request, capabilities, added, replaced, availability, userFlags);
        }

        // --- the rest of the preset -----------------------------------------
        addPublishAllActions(request, capabilities, added, conflicts, availability, userFlags);
        addExecutionLog(request, capabilities, added, outputs, availability, warnings, userFlags);
        addProfile(request, capabilities, added, outputs, availability, warnings, userFlags);
        reportUnimplemented(request, availability, warnings);

        // --- destinations ----------------------------------------------------
        for (Path output : outputs) {
            if (Files.exists(output) && !request.allowOverwrite()) {
                conflicts.add(destinationExistsConflict(output));
            }
        }

        // --- assemble ---------------------------------------------------------
        BazelCommand effective = applyTo(original, added);
        if (userFlags.hasSeparator() && !added.isEmpty()) {
            warnings.add("This command has a '--' separator. Instrumentation flags were placed"
                    + " before it, because everything after '--' is read as a target pattern.");
        }

        return new InstrumentationPlan(
                original,
                effective,
                List.copyOf(added),
                List.copyOf(replaced),
                List.of(),
                List.copyOf(conflicts),
                List.copyOf(warnings),
                List.copyOf(errors),
                List.copyOf(outputs),
                new SourceAvailability(availability),
                request.preset());
    }

    // ------------------------------------------------------------- the catalog

    private void addBesBackend(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            List<ReplacedFlag> replaced,
            Map<DataSource, SourceAvailability.Entry> availability,
            UserFlags userFlags) {
        CapabilityStatus status = capabilities.status(Capability.BES_BACKEND);
        String flagName = capabilities.preferredFlag(Capability.BES_BACKEND).orElse("bes_backend");
        String endpoint = request.besEndpoint().orElseThrow();

        added.add(new AddedFlag(
                "--" + flagName + "=" + endpoint,
                AddedFlag.Placement.COMMAND,
                Capability.BES_BACKEND,
                status,
                "Sends the build event stream to this application, over loopback only.",
                DataSource.BES_ENVELOPE,
                Overhead.LOW,
                Optional.empty(),
                false,
                // Without it there is no live capture at all, so it is the one
                // flag the dialog does not offer to remove.
                false));

        if (userFlags.has("bes_backend")
                && request.resolutionFor(PlanConflict.Kind.EXISTING_BES_BACKEND)
                        .map(PlanConflict.RESOLUTION_REPLACE_BES::equals)
                        .orElse(false)) {
            replaced.add(new ReplacedFlag(
                    userFlags.raw("bes_backend"),
                    "--" + flagName + "=" + endpoint,
                    PlanConflict.RESOLUTION_REPLACE_BES,
                    "Build results will go to this application instead of the backend you named."
                            + " Bazel takes the last value on the command line and reports nothing"
                            + " about the one it shadowed."));
        }

        addBesTimeout(request, capabilities, added, userFlags);

        availability.put(DataSource.BES_ENVELOPE, new SourceAvailability.Entry(
                status.isSupported()
                        ? SourceAvailability.Availability.PLANNED
                        : SourceAvailability.Availability.UNAVAILABLE,
                status.isSupported()
                        ? "captured live through the embedded Build Event Service"
                        : explain(status, "--" + flagName),
                Optional.of("--" + flagName)));
        availability.put(DataSource.BEP, new SourceAvailability.Entry(
                status.isSupported()
                        ? SourceAvailability.Availability.PLANNED
                        : SourceAvailability.Availability.UNAVAILABLE,
                status.isSupported()
                        ? "every build event arrives inside the BES stream"
                        : explain(status, "--" + flagName),
                Optional.of("--" + flagName)));
    }

    /**
     * Bounds the wait for the upload, so a fault in this application cannot
     * hang the user's build.
     *
     * <p>Skipped when the user set their own {@code --bes_timeout}: they have
     * expressed an intent about how long to wait, and overriding it to protect
     * them from us would be presumptuous. The plan still shows the flag, marked
     * as not applied, so the choice is visible.
     */
    private void addBesTimeout(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            UserFlags userFlags) {
        CapabilityStatus status = capabilities.status(Capability.BES_TIMEOUT);
        String flagName = capabilities.preferredFlag(Capability.BES_TIMEOUT).orElse("bes_timeout");
        if (userFlags.has(flagName)) {
            return;
        }
        added.add(new AddedFlag(
                "--" + flagName + "=" + BES_TIMEOUT_VALUE,
                AddedFlag.Placement.COMMAND,
                Capability.BES_TIMEOUT,
                request.vetoed().contains(Capability.BES_TIMEOUT)
                        ? CapabilityStatus.UNSUPPORTED
                        : status,
                "Bounds how long Bazel waits for this application to acknowledge the event"
                        + " stream. Bazel's own default is to wait forever, so without this a"
                        + " fault here would hang your build with no error.",
                DataSource.BES_ENVELOPE,
                Overhead.LOW,
                Optional.empty(),
                false,
                true));
    }

    private void addBepFileFallback(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            List<Path> outputs,
            Map<DataSource, SourceAvailability.Entry> availability,
            List<String> warnings,
            List<PlanConflict> conflicts,
            List<ReplacedFlag> replaced,
            UserFlags userFlags) {
        CapabilityStatus status = capabilities.status(Capability.BEP_BINARY_FILE);
        String flagName = capabilities.preferredFlag(Capability.BEP_BINARY_FILE)
                .orElse("build_event_binary_file");
        Path file = request.sessionRawDirectory().resolve(FALLBACK_BEP_FILE).toAbsolutePath();

        // The user may already be writing a BEP file for something else — a CI
        // step that consumes it downstream is the ordinary case. This flag is
        // single-valued and injected flags are appended, so adding ours would
        // silently win and their file would never be written. That is exactly
        // the silent override the contract forbids, so it is a decision, not a
        // default.
        if (userFlags.has(flagName)) {
            String chosen = request.resolutionFor(PlanConflict.Kind.EXISTING_BEP_OUTPUT).orElse(null);
            if (chosen == null) {
                conflicts.add(existingBepOutputConflict(
                        userFlags.raw(flagName), userFlags.originOf(flagName)));
                return;
            }
            if (RESOLUTION_READ_USER_BEP_FILE.equals(chosen)) {
                // Their file, read where it lands. Nothing is injected, so
                // nothing of theirs is overridden.
                Optional<String> theirPath = CommandLineParser.attachedValue(userFlags.raw(flagName));
                availability.put(DataSource.BEP, new SourceAvailability.Entry(
                        SourceAvailability.Availability.PLANNED,
                        "captured from the build event file your command already writes",
                        Optional.of("--" + flagName)));
                // Deliberately not added to expectedOutputs: that list is
                // "files this plan causes to be written", and this one is
                // written by the user's own flag whether or not we are here.
                // Listing it would raise a DESTINATION_EXISTS conflict about a
                // file we are not going to touch.
                theirPath.ifPresent(path -> warnings.add(
                        "Reading the build event file your command already writes: " + path));
                return;
            }
            if (PlanConflict.RESOLUTION_CANCEL.equals(chosen)) {
                return;
            }
            // RESOLUTION_REDIRECT_BEP_FILE falls through and is recorded below.
        }

        added.add(new AddedFlag(
                "--" + flagName + "=" + file,
                AddedFlag.Placement.COMMAND,
                Capability.BEP_BINARY_FILE,
                status,
                "Writes the event stream to a file this application reads, leaving your own"
                        + " Build Event Service backend untouched.",
                DataSource.BEP,
                Overhead.MEDIUM,
                Optional.of(file),
                // A BEP file names every target, every output path and the full
                // command line, so it can carry absolute paths and arguments.
                true,
                false));
        outputs.add(file);
        warnings.add("Your own --bes_backend is being kept, so events are captured through a local"
                + " file instead. Capture finishes when the build does, rather than arriving live.");
        if (userFlags.has(flagName)) {
            replaced.add(new ReplacedFlag(
                    userFlags.raw(flagName),
                    "--" + flagName + "=" + file,
                    RESOLUTION_REDIRECT_BEP_FILE,
                    "Your build event file will not be written. Bazel takes the last value on the"
                            + " command line and reports nothing about the one it shadowed."));
        }

        availability.put(DataSource.BEP, new SourceAvailability.Entry(
                status.isSupported()
                        ? SourceAvailability.Availability.PLANNED
                        : SourceAvailability.Availability.UNAVAILABLE,
                status.isSupported()
                        ? "captured from a local build event file after the build"
                        : explain(status, "--" + flagName),
                Optional.of("--" + flagName)));
        availability.put(DataSource.BES_ENVELOPE, new SourceAvailability.Entry(
                SourceAvailability.Availability.DECLINED,
                "your own Build Event Service backend was kept, so no events pass through this"
                        + " application's server",
                Optional.empty()));
    }

    private void addPublishAllActions(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            List<PlanConflict> conflicts,
            Map<DataSource, SourceAvailability.Entry> availability,
            UserFlags userFlags) {
        if (!request.preset().requestedCapabilities().contains(Capability.PUBLISH_ALL_ACTIONS)) {
            return;
        }
        if (request.vetoed().contains(Capability.PUBLISH_ALL_ACTIONS)) {
            return;
        }
        CapabilityStatus status = capabilities.status(Capability.PUBLISH_ALL_ACTIONS);
        String flagName = capabilities.preferredFlag(Capability.PUBLISH_ALL_ACTIONS)
                .orElse("build_event_publish_all_actions");

        if (userFlags.hasNegated(flagName)) {
            conflicts.add(new PlanConflict(
                    PlanConflict.Kind.ACTION_PUBLICATION_DISABLED,
                    false,
                    "Your command turns action publication off",
                    "--no" + flagName + " is on your command line. It is being left alone, so the"
                            + " session will show which targets built but not which actions ran.",
                    List.of(),
                    Optional.of("--no" + flagName)));
            availability.put(DataSource.BEP, availability.getOrDefault(
                    DataSource.BEP,
                    new SourceAvailability.Entry(
                            SourceAvailability.Availability.PLANNED,
                            "captured, but without per-action events",
                            Optional.empty())));
            return;
        }

        added.add(new AddedFlag(
                "--" + flagName,
                AddedFlag.Placement.COMMAND,
                Capability.PUBLISH_ALL_ACTIONS,
                status,
                "Publishes an event for every action, not only failed ones. Without it a"
                        + " successful action produces no event at all, and the action views are"
                        + " empty for a build that worked.",
                DataSource.BEP,
                // The single largest driver of event volume: on a build of 20
                // targets it multiplies the stream several times over, and it
                // scales with the number of actions rather than targets.
                Overhead.HIGH,
                Optional.empty(),
                false,
                true));
    }

    /**
     * States the sources this phase does not capture yet.
     *
     * <p>The presets name them, so leaving them out of the availability map
     * would let a later view render nothing with no explanation. Saying
     * "not implemented in this version" is a worse answer than capturing them
     * and a much better one than silence.
     */

    /**
     * The execution log: which format, and the flags that make it usable.
     *
     * <h2>Format choice</h2>
     *
     * <p>Compact where it exists, binary otherwise. The compact format is
     * 3.5x smaller than binary for identical content and is the only one
     * carrying an invocation header, which is the only way a log can be shown
     * to belong to its session (S4, V2 in docs/exec-log-and-profile.md).
     * Bazel 6.5.0 has no compact format at all (X1).
     *
     * <p>Only one may be asked for: from Bazel 7 on, naming two is a
     * command-line error that fails the build before analysis (X2). So this
     * adds exactly one flag and never a fallback.
     *
     * <h2>The 6.5.0 extra</h2>
     *
     * <p>{@code --experimental_execution_log_spawn_metrics} exists only on
     * 6.5.0 and defaults to false. Without it a 6.5.0 log carries no metrics
     * submessage at all. With it there are durations — and still no spawn
     * start, on any setting (S2), which is why the attempt rows from that
     * version say so rather than leaving a blank.
     */
    private void addExecutionLog(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            List<Path> outputs,
            Map<DataSource, SourceAvailability.Entry> availability,
            List<String> warnings,
            UserFlags userFlags) {
        if (!request.preset().requestedCapabilities().contains(Capability.EXECUTION_LOG_COMPACT)) {
            return;
        }

        Capability chosen = Capability.EXECUTION_LOG_COMPACT;
        String fileName = EXECUTION_LOG_FILE;
        if (!capabilities.status(chosen).isSupported()) {
            chosen = Capability.EXECUTION_LOG_BINARY;
            fileName = EXECUTION_LOG_BINARY_FILE;
        }
        CapabilityStatus status = capabilities.status(chosen);
        if (!status.isSupported()) {
            availability.put(DataSource.EXECUTION_LOG, new SourceAvailability.Entry(
                    SourceAvailability.Availability.UNAVAILABLE,
                    explain(status, "--execution_log_compact_file"),
                    Optional.empty()));
            return;
        }

        String flagName = capabilities.preferredFlag(chosen).orElse(chosen.flagNames().getFirst());
        Path file = request.sessionRawDirectory().resolve(fileName).toAbsolutePath();

        // Their flag wins if they named one: the formats are mutually
        // exclusive, so adding ours alongside theirs fails the build outright
        // rather than shadowing it.
        for (Capability format : List.of(Capability.EXECUTION_LOG_COMPACT,
                Capability.EXECUTION_LOG_BINARY, Capability.EXECUTION_LOG_JSON)) {
            for (String name : format.flagNames()) {
                if (userFlags.has(name)) {
                    availability.put(DataSource.EXECUTION_LOG, new SourceAvailability.Entry(
                            SourceAvailability.Availability.DECLINED,
                            "your command already writes an execution log, and Bazel accepts only"
                                    + " one format at a time",
                            Optional.of("--" + name)));
                    warnings.add("Your command already writes an execution log with --" + name
                            + ", and Bazel refuses more than one format in a single invocation."
                            + " No execution-log flag was added; import that file afterwards.");
                    return;
                }
            }
        }

        added.add(new AddedFlag(
                "--" + flagName + "=" + file,
                AddedFlag.Placement.COMMAND,
                chosen,
                status,
                "Records every subprocess the build ran: where it ran, whether it was a cache"
                        + " hit, and where its time went.",
                DataSource.EXECUTION_LOG,
                Overhead.MEDIUM,
                Optional.of(file),
                // Every spawn's argv and environment, so absolute paths,
                // command arguments and environment values all appear.
                true,
                true));
        outputs.add(file);

        // 6.5.0 only, and off by default. Adds durations; never adds a start.
        CapabilityStatus spawnMetrics = capabilities.status(Capability.EXECUTION_LOG_SPAWN_METRICS);
        if (spawnMetrics.isSupported()) {
            added.add(new AddedFlag(
                    "--experimental_execution_log_spawn_metrics",
                    AddedFlag.Placement.COMMAND,
                    Capability.EXECUTION_LOG_SPAWN_METRICS,
                    spawnMetrics,
                    "Makes this Bazel report how long each subprocess took. Without it the log"
                            + " records what ran and not how long it took.",
                    DataSource.EXECUTION_LOG,
                    Overhead.LOW,
                    Optional.empty(),
                    false,
                    true));
        }

        availability.put(DataSource.EXECUTION_LOG, new SourceAvailability.Entry(
                SourceAvailability.Availability.PLANNED,
                chosen == Capability.EXECUTION_LOG_COMPACT
                        ? "captured in the compact format after the build"
                        : "captured in the pre-compact binary format, which this Bazel is the"
                                + " only supported version to require",
                Optional.of("--" + flagName)));
    }

    /**
     * The trace profile, and the three flags without which it is not worth
     * importing.
     *
     * <p>{@code --noslim_profile}: slimming is the default on every supported
     * version and cuts per-action events from 22–30 down to 2 (X3). A profile
     * captured without this has none of what Phase 4 reads it for.
     *
     * <p>{@code --experimental_profile_include_primary_output}: the {@code out}
     * field is the only thing tying a span to an action, and it defaults off
     * (P4).
     *
     * <p>{@code --experimental_profile_include_target_label}: useful but not
     * load-bearing — {@code args.target} was measured empty on 7.6.1 for the
     * workspace-status action, so {@code out} is the key that is relied on.
     */
    private void addProfile(
            PlanRequest request,
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            List<Path> outputs,
            Map<DataSource, SourceAvailability.Entry> availability,
            List<String> warnings,
            UserFlags userFlags) {
        if (!request.preset().requestedCapabilities().contains(Capability.JSON_TRACE_PROFILE)) {
            return;
        }
        CapabilityStatus status = capabilities.status(Capability.PROFILE_PATH);
        if (!status.isSupported()) {
            availability.put(DataSource.PROFILE, new SourceAvailability.Entry(
                    SourceAvailability.Availability.UNAVAILABLE,
                    explain(status, "--profile"),
                    Optional.empty()));
            return;
        }
        if (userFlags.has("profile")) {
            availability.put(DataSource.PROFILE, new SourceAvailability.Entry(
                    SourceAvailability.Availability.DECLINED,
                    "your command already chooses where the profile is written",
                    Optional.of("--profile")));
            warnings.add("Your command already sets --profile, so no profile flag was added."
                    + " Import that file afterwards if you want its phases and critical path.");
            return;
        }

        Path file = request.sessionRawDirectory().resolve(PROFILE_FILE).toAbsolutePath();
        added.add(new AddedFlag(
                "--profile=" + file,
                AddedFlag.Placement.COMMAND,
                Capability.PROFILE_PATH,
                status,
                "Writes the trace profile where this application can read it.",
                DataSource.PROFILE,
                Overhead.MEDIUM,
                Optional.of(file),
                // Span names carry target labels and output paths.
                true,
                true));
        outputs.add(file);

        addProfileSwitch(capabilities, added, Capability.JSON_TRACE_PROFILE,
                "--generate_json_trace_profile",
                "Turns the profile on explicitly rather than relying on its default.");
        addProfileSwitch(capabilities, added, Capability.UNSLIM_PROFILE,
                "--noslim_profile",
                "Keeps the per-action events. Slimming is Bazel's default and cuts them from"
                        + " about thirty to two, which is all of what this application reads a"
                        + " profile for.");
        addProfileSwitch(capabilities, added, Capability.PROFILE_PRIMARY_OUTPUT,
                "--experimental_profile_include_primary_output",
                "Labels each span with the output it produced. Without it no span can be tied"
                        + " to an action.");
        addProfileSwitch(capabilities, added, Capability.PROFILE_TARGET_LABELS,
                "--experimental_profile_include_target_label",
                "Labels each span with its target.");

        availability.put(DataSource.PROFILE, new SourceAvailability.Entry(
                SourceAvailability.Availability.PLANNED,
                "captured after the build, unslimmed and with per-action attribution",
                Optional.of("--profile")));
    }

    private void addProfileSwitch(
            BazelCapabilities capabilities,
            List<AddedFlag> added,
            Capability capability,
            String flag,
            String why) {
        CapabilityStatus status = capabilities.status(capability);
        if (!status.isSupported()) {
            return;
        }
        added.add(new AddedFlag(
                flag,
                AddedFlag.Placement.COMMAND,
                capability,
                status,
                why,
                DataSource.PROFILE,
                Overhead.LOW,
                Optional.empty(),
                false,
                true));
    }

    private void reportUnimplemented(
            PlanRequest request,
            Map<DataSource, SourceAvailability.Entry> availability,
            List<String> warnings) {
        Map<DataSource, Capability> pending = new LinkedHashMap<>();
        pending.put(DataSource.AQUERY, Capability.AQUERY_PROTO_OUTPUT);
        pending.put(DataSource.CQUERY, Capability.CQUERY_PROTO_OUTPUT);

        List<String> requested = new ArrayList<>();
        pending.forEach((source, capability) -> {
            if (!request.preset().requestedCapabilities().contains(capability)) {
                return;
            }
            requested.add(source.name().toLowerCase(java.util.Locale.ROOT));
            availability.put(source, new SourceAvailability.Entry(
                    SourceAvailability.Availability.UNAVAILABLE,
                    "this version of the application does not capture it yet",
                    Optional.empty()));
        });
        if (!requested.isEmpty()) {
            warnings.add("The " + request.preset().displayName() + " preset asks for "
                    + String.join(", ", requested)
                    + ", which this version does not capture yet. Everything else in the preset is"
                    + " unaffected.");
        }
    }

    // ------------------------------------------------------------- conflicts

    private static PlanConflict existingBesBackendConflict(String offending, String origin) {
        return new PlanConflict(
                PlanConflict.Kind.EXISTING_BES_BACKEND,
                true,
                "This build already sends its results somewhere",
                "'" + offending + "' " + origin + ". Two Build Event Service backends"
                        + " cannot both receive this build, and this application does not forward"
                        + " events on to another one.",
                List.of(
                        new PlanConflict.Resolution(
                                PlanConflict.RESOLUTION_REPLACE_BES,
                                "Send results here instead",
                                "Your backend will not receive this build. Anything your team relies"
                                        + " on it for — dashboards, result links, retention — will"
                                        + " have no record of this invocation."),
                        new PlanConflict.Resolution(
                                PlanConflict.RESOLUTION_KEEP_BES_USE_FILE,
                                "Keep yours, capture through a local file",
                                "Your backend still receives everything. This application reads a"
                                        + " local copy instead, so capture completes when the build"
                                        + " does rather than arriving live."),
                        new PlanConflict.Resolution(
                                PlanConflict.RESOLUTION_CANCEL,
                                "Cancel and edit the command",
                                "Nothing runs and nothing is captured.")),
                Optional.of(offending));
    }

    /** Everything Bazel reads as false for a boolean option. */
    private static final Set<String> FALSE_SPELLINGS = Set.of("false", "no", "0");

    /** Resolution ids for a user who is already writing a build event file. */
    public static final String RESOLUTION_REDIRECT_BEP_FILE = "redirect-bep-file";
    public static final String RESOLUTION_READ_USER_BEP_FILE = "read-user-bep-file";

    private static PlanConflict existingBepOutputConflict(String offending, String origin) {
        return new PlanConflict(
                PlanConflict.Kind.EXISTING_BEP_OUTPUT,
                true,
                "This build already writes a build event file",
                "'" + offending + "' " + origin + ". Bazel writes only the last one it is"
                        + " given, so adding a second would silently stop yours from being written.",
                List.of(
                        new PlanConflict.Resolution(
                                RESOLUTION_READ_USER_BEP_FILE,
                                "Read the file you already write",
                                "Nothing is added to your command. This application reads the file"
                                        + " your build writes, so whatever consumes it downstream"
                                        + " still gets it."),
                        new PlanConflict.Resolution(
                                RESOLUTION_REDIRECT_BEP_FILE,
                                "Write it into the session instead",
                                "Your file will not be written at all, and anything downstream that"
                                        + " reads it will see nothing, or last run's copy."),
                        new PlanConflict.Resolution(
                                PlanConflict.RESOLUTION_CANCEL,
                                "Cancel and edit the command",
                                "Nothing runs and nothing is captured.")),
                Optional.of(offending));
    }

    private static PlanConflict destinationExistsConflict(Path file) {
        return new PlanConflict(
                PlanConflict.Kind.DESTINATION_EXISTS,
                true,
                "A file this build would write already exists",
                file + " is already there. Bazel truncates it on start, so launching would destroy"
                        + " whatever it contains.",
                List.of(
                        new PlanConflict.Resolution(
                                "overwrite",
                                "Overwrite it",
                                "The existing file is lost."),
                        new PlanConflict.Resolution(
                                PlanConflict.RESOLUTION_CANCEL,
                                "Cancel",
                                "Nothing runs and nothing is captured.")),
                Optional.empty());
    }

    // ------------------------------------------------------------- assembly

    /** Appends the applied flags to the command options, ahead of the targets. */
    private static BazelCommand applyTo(BazelCommand original, List<AddedFlag> added) {
        List<String> commandArgs = new ArrayList<>(original.commandArgs());
        for (AddedFlag flag : added) {
            if (flag.isApplied() && flag.placement() == AddedFlag.Placement.COMMAND) {
                commandArgs.add(flag.argv());
            }
        }
        return original.toBuilder().commandArgs(commandArgs).build();
    }

    /**
     * Whether the selected binary publishes events for this command.
     *
     * <p>Asked of the capability table rather than of a hard-coded list of
     * command names, because the list differs between versions — {@code sync}
     * and {@code analyze-profile} accept BES flags on Bazel 8 and not on 9 —
     * and a hard-coded list would refuse to instrument a command the user's
     * Bazel is perfectly willing to instrument.
     */
    private static boolean isInstrumentable(String command, BazelCapabilities capabilities) {
        if (capabilities.isUnprobed()) {
            // Nothing was learned about this binary. Refusing every command
            // would make an unprobed Bazel unusable; the individual flags still
            // report UNKNOWN and are not injected.
            return true;
        }
        if (capabilities.detection() == BazelCapabilities.DetectionMethod.HELP_TEXT) {
            // The text fallback scrapes a fixed handful of commands, so a
            // command it did not scrape has an empty command set — which is
            // absence of evidence, not evidence of absence. Refusing on that
            // basis told a user that `bazel run` does not publish events, which
            // is false, and blocked a build that would have worked.
            return true;
        }
        return capabilities.flag("bes_backend").map(spec -> spec.appliesTo(command)).orElse(false);
    }

    private static String explain(CapabilityStatus status, String flag) {
        return switch (status) {
            case SUPPORTED -> "available";
            case UNSUPPORTED -> "the selected Bazel does not accept " + flag;
            case UNKNOWN -> "the selected Bazel could not be probed, so " + flag
                    + " was not used; it may or may not be available";
        };
    }

    /**
     * The options that will apply to the build, indexed by name, and where each
     * came from.
     *
     * <p>The origin matters for the message, not just the decision. Telling
     * someone their {@code --bes_backend} "is on your command line" when they
     * set it in a workspace {@code .bazelrc} sends them looking in the wrong
     * place, and the whole purpose of the conflict is to help them decide.
     */
    private record UserFlags(
            Map<String, String> byName, Set<String> fromRcFiles, boolean hasSeparator) {

        /**
         * @param effectiveOptions what Bazel will really apply, when it could be
         *     asked. Merged <em>under</em> the typed argv so that a flag the
         *     user typed is reported with the spelling they typed, which is
         *     what a conflict message has to quote back to them.
         */
        static UserFlags of(BazelCommand command, Optional<List<String>> effectiveOptions) {
            Map<String, String> byName = new LinkedHashMap<>();
            Set<String> fromRc = new java.util.LinkedHashSet<>();
            effectiveOptions.ifPresent(options -> {
                for (String token : options) {
                    CommandLineParser.flagName(token).ifPresent(name -> {
                        byName.put(name, token);
                        fromRc.add(name);
                    });
                }
            });
            List<String> typed = new ArrayList<>(command.startupArgs());
            typed.addAll(command.commandArgs());
            for (String token : typed) {
                CommandLineParser.flagName(token).ifPresent(name -> {
                    byName.put(name, token);
                    fromRc.remove(name);
                });
            }
            return new UserFlags(byName, fromRc, !command.argsAfterDoubleDash().isEmpty());
        }

        /** Where this option came from, in words a message can use. */
        String originOf(String flagName) {
            return fromRcFiles.contains(flagName) || fromRcFiles.contains("no" + flagName)
                    ? "is set in a .bazelrc file that applies to this build"
                    : "is on your command line";
        }

        boolean has(String flagName) {
            return byName.containsKey(flagName) || byName.containsKey("no" + flagName);
        }

        /**
         * Whether this option is set to false, in any spelling Bazel accepts.
         *
         * <p>Bazel reads {@code false}, {@code no} and {@code 0} as false and
         * {@code true}, {@code yes} and {@code 1} as true, case-insensitively,
         * as well as the {@code --noflag} prefix form. Recognising only two of
         * them meant {@code --build_event_publish_all_actions=no} looked like a
         * flag the user had not set, and the planner injected the positive form
         * over it without raising the conflict that exists to prevent exactly
         * that.
         */
        boolean hasNegated(String flagName) {
            if (byName.containsKey("no" + flagName)) {
                return true;
            }
            String value = valueOf(flagName);
            return value != null && FALSE_SPELLINGS.contains(value.toLowerCase(java.util.Locale.ROOT));
        }

        String raw(String flagName) {
            String exact = byName.get(flagName);
            return exact != null ? exact : byName.getOrDefault("no" + flagName, "--" + flagName);
        }

        private String valueOf(String flagName) {
            String token = byName.get(flagName);
            return token == null ? null : CommandLineParser.attachedValue(token).orElse(null);
        }
    }
}

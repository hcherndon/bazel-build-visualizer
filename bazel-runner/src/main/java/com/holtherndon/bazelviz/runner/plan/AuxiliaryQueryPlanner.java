package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.runner.caps.BazelCapabilities;
import com.holtherndon.bazelviz.runner.caps.FlagSpec;
import com.holtherndon.bazelviz.runner.command.BazelCommand;
import com.holtherndon.bazelviz.runner.command.CommandLineParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@code aquery} and {@code cquery} commands that describe a build
 * that has already run.
 *
 * <h2>The rules, and why each one is not a preference</h2>
 *
 * <p>Plan 8.6 lists ten. The four that shape this class:
 *
 * <ul>
 *   <li><b>Reuse the executable, the startup options, the workspace and the
 *       environment.</b> A query run against a different Bazel analyses a
 *       different graph, and startup options choose the output base — a query
 *       against the wrong one re-analyses from scratch and may not even agree
 *       about the configuration.
 *   <li><b>Retain the configuration-affecting options the auxiliary command
 *       supports.</b> {@code --compilation_mode}, {@code --platforms},
 *       {@code --define} and their like decide which graph exists at all. Drop
 *       one and the query answers about a build nobody ran.
 *   <li><b>Exclude the options the auxiliary command rejects.</b> Passing
 *       {@code --build_event_binary_file} to {@code aquery} is an error that
 *       fails the query, so an option the binary does not accept for this
 *       command is left out — and named, because leaving it out is what makes
 *       the graph possibly not match.
 *   <li><b>Explain when the graph may not match because options could not be
 *       reproduced.</b> That is {@link Plan#droppedOptions()}, and it is the
 *       reason the plan is a record rather than a list of strings.
 * </ul>
 *
 * <p>What this class does <em>not</em> do is decide whether the graph matched.
 * That is checked afterwards, against the configurations the query actually
 * reported (finding Q6), because an option surviving into the command line is
 * not proof that it had the same effect.
 */
public final class AuxiliaryQueryPlanner {

    /**
     * Options never carried into a query, whatever the binary claims.
     *
     * <p>Every one of them asks Bazel to <em>do</em> something — publish
     * events, write a log, run tests — which a query neither can nor should.
     * Matching is on the option name before any {@code =}.
     */
    private static final Set<String> NEVER_CARRIED = Set.of(
            "bes_backend", "bes_lifecycle_events", "bes_timeout", "bes_results_url",
            "build_event_binary_file", "build_event_json_file", "build_event_text_file",
            "build_event_publish_all_actions", "build_event_binary_file_upload_mode",
            "execution_log_compact_file", "execution_log_binary_file", "execution_log_json_file",
            "experimental_execution_log_spawn_metrics",
            "profile", "generate_json_trace_profile", "slim_profile",
            "experimental_profile_include_target_label",
            "experimental_profile_include_primary_output",
            "memory_profile", "keep_going", "nokeep_going", "jobs", "test_output",
            "runs_per_test", "flaky_test_attempts", "test_filter", "cache_test_results",
            "nocache_test_results", "noslim_profile", "nogenerate_json_trace_profile",
            "nobuild_event_publish_all_actions");

    private final BazelCapabilities capabilities;

    public AuxiliaryQueryPlanner(BazelCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /** The {@code aquery} that describes {@code original}'s action graph. */
    public Plan aquery(BazelCommand original, Path outputFile) {
        return plan(original, "aquery", outputFile);
    }

    /** The {@code cquery} that describes {@code original}'s configured targets. */
    public Plan cquery(BazelCommand original, Path outputFile) {
        return plan(original, "cquery", outputFile);
    }

    private Plan plan(BazelCommand original, String command, Path outputFile) {
        List<String> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        for (String argument : original.commandArgs()) {
            String name = optionName(argument);
            if (name == null) {
                // Not an option. Targets are taken from the command's own
                // target list, so a bare word here is not carried.
                continue;
            }
            if (NEVER_CARRIED.contains(name)) {
                // Deliberate, not a failure to reproduce: these ask for work a
                // query does not do, so their absence changes no graph.
                continue;
            }
            if (accepts(name, command)) {
                kept.add(argument);
            } else {
                dropped.add(argument);
            }
        }

        List<String> arguments = new ArrayList<>(kept);
        arguments.add("--output=proto");
        List<String> targets = original.targets().isEmpty()
                ? List.of("//...") : List.copyOf(original.targets());

        BazelCommand queryCommand = new BazelCommand(
                original.executable(),
                // Startup options choose the output base, so a query that
                // dropped them would analyse in a different server.
                original.startupArgs(),
                command,
                arguments,
                targets,
                List.of(),
                original.workingDirectory(),
                original.environmentOverrides(),
                original.inheritance(),
                // Never a shell: the argv is built here and nothing is
                // interpolated into a command string (plan 22.2).
                false);

        return new Plan(queryCommand, outputFile, List.copyOf(dropped), targetNote(original));
    }

    /**
     * Whether this binary accepts {@code name} for {@code command}.
     *
     * <p>An unknown option is kept rather than dropped. The capability table
     * comes from {@code bazel help}, which does not list every option a Bazel
     * accepts — Starlark flags, for one, appear nowhere in it. Dropping an
     * option this build has never heard of would silently change the graph;
     * keeping it makes the query fail loudly if it really is rejected, and a
     * failed query is a state Phase 5 already reports.
     */
    private boolean accepts(String name, String command) {
        Optional<FlagSpec> spec = capabilities.flag(name);
        return spec.map(found -> found.appliesTo(command)).orElse(true);
    }

    /**
     * {@code --foo} or {@code --foo=bar} to {@code foo}; null for a non-option.
     *
     * <p>Returns the name as written. A boolean option's negated spelling —
     * {@code --nokeep_going} — is a different string from {@code keep_going},
     * and this deliberately does not unify them: stripping a leading "no"
     * would turn {@code --notify} into {@code tify} and, worse, would let
     * {@code --nobuild} match a rule written for {@code build}. The negated
     * spellings that matter are listed in {@link #NEVER_CARRIED} beside their
     * positive forms.
     */
    static String optionName(String argument) {
        if (!argument.startsWith("--") || argument.equals("--")) {
            return null;
        }
        String body = argument.substring(2);
        int equals = body.indexOf('=');
        return equals < 0 ? body : body.substring(0, equals);
    }

    private static Optional<String> targetNote(BazelCommand original) {
        return original.targets().isEmpty()
                ? Optional.of("The build named no targets, so the query asks about //... ,"
                        + " which may cover more than the build did.")
                : Optional.empty();
    }

    /**
     * One auxiliary query, ready to run and honest about what it lost.
     *
     * @param droppedOptions options the build had that this command does not
     *     accept. Non-empty means the graph may not match, and plan 8.6 step 10
     *     requires saying so before it is presented as the build's.
     * @param note anything else the user should know before reading the result
     */
    public record Plan(
            BazelCommand command,
            Path outputFile,
            List<String> droppedOptions,
            Optional<String> note) {

        public Plan {
            droppedOptions = List.copyOf(droppedOptions);
        }

        /** The argv, for display and for launching. */
        public List<String> argv() {
            return command.toArgv();
        }

        /** True when every option the build carried survived into the query. */
        public boolean reproducesOptions() {
            return droppedOptions.isEmpty();
        }

        /**
         * Why the graph might not match, or empty when nothing was lost.
         *
         * <p>Deliberately hedged: an option surviving is not proof it had the
         * same effect, so this says "may not" and the real answer comes from
         * comparing the configurations the query reported.
         */
        public Optional<String> mismatchWarning() {
            if (droppedOptions.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of("This query could not carry " + droppedOptions.size()
                    + " of the build's options, because " + command.command()
                    + " does not accept them: " + String.join(" ", droppedOptions)
                    + ". The graph it returns may describe a different configuration"
                    + " than the build used.");
        }
    }

    /** Parses a command line and plans both queries for it. */
    public static List<Plan> forCommandLine(
            BazelCapabilities capabilities,
            Path executable,
            Path workingDirectory,
            List<String> argv,
            Path aqueryOutput,
            Path cqueryOutput) {
        BazelCommand original = new CommandLineParser(Optional.of(capabilities))
                .parse(executable, workingDirectory, argv);
        AuxiliaryQueryPlanner planner = new AuxiliaryQueryPlanner(capabilities);
        List<Plan> plans = new ArrayList<>();
        plans.add(planner.aquery(original, aqueryOutput));
        plans.add(planner.cquery(original, cqueryOutput));
        return plans;
    }

    /** Every option name this planner refuses to carry, for tests and for the UI. */
    public static Set<String> neverCarried() {
        return new LinkedHashSet<>(NEVER_CARRIED);
    }
}

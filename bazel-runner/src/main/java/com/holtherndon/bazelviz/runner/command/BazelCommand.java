package com.holtherndon.bazelviz.runner.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A Bazel launch as structured data (plan 8.1).
 *
 * <h2>Why this is not a string</h2>
 *
 * <p>The instrumentation planner has to insert flags in the one position Bazel
 * accepts them: startup options go before the command, command options after it,
 * and everything past {@code --} is target-residue that Bazel never interprets
 * as a flag. A command held as one string cannot be edited that way without
 * re-lexing it and guessing at quoting, and guessing wrong means either a flag
 * that silently does nothing or a target name mangled into an option. So the
 * command is split once, on the way in, and stays split.
 *
 * <p>It is also the reason {@link #toArgv()} can promise that nothing is ever
 * shell-quoted: each element becomes one {@code argv} entry verbatim (plan 8.4,
 * plan 22.3). Shell mode exists for users who genuinely need expansion, and
 * carries its own warning; it is not the default and it is not reachable by
 * accident.
 *
 * <h2>The parts</h2>
 *
 * <pre>{@code
 *   bazel --output_base=/tmp/ob   test   --keep_going   //foo:all   --   --gtest_filter=X
 *   ^executable  ^startupArgs     ^command ^commandArgs  ^targets    ^^  ^argsAfterDoubleDash
 * }</pre>
 *
 * <p>{@link #commandArgs} and {@link #targets} are kept apart even though Bazel
 * accepts them interleaved, because the auxiliary-command planner (plan 8.6) has
 * to extract the targets to build an {@code aquery} while dropping options the
 * auxiliary command would reject. It cannot do that from a flat list without
 * re-deciding which entries were targets.
 *
 * @param executable the resolved Bazel/Bazelisk binary, absolute
 * @param startupArgs options before the command; Bazel restarts its server when
 *     these change, which is why the planner never adds one silently
 * @param command the Bazel command, for example {@code build} or {@code test};
 *     empty for a bare {@code bazel} invocation, which is a usage error rather
 *     than a launchable command
 * @param commandArgs options after the command, excluding target residue
 * @param targets the target patterns
 * @param argsAfterDoubleDash arguments passed through to the built binary or
 *     test, never interpreted by Bazel or by this application
 * @param workingDirectory the process working directory; Bazel resolves relative
 *     target patterns against it, so it is recorded separately from the detected
 *     workspace root (plan 8.2)
 * @param environmentOverrides variables to set or, when the value is empty, to
 *     remove from the inherited environment
 * @param inheritance what the child process inherits from this one
 * @param shellMode true when the user asked for the command to run through a
 *     shell; carries a security warning in the UI and is never set by the
 *     planner
 */
public record BazelCommand(
        Path executable,
        List<String> startupArgs,
        String command,
        List<String> commandArgs,
        List<String> targets,
        List<String> argsAfterDoubleDash,
        Path workingDirectory,
        Map<String, Optional<String>> environmentOverrides,
        EnvironmentInheritance inheritance,
        boolean shellMode) {

    public BazelCommand {
        Objects.requireNonNull(executable, "executable");
        startupArgs = List.copyOf(startupArgs);
        Objects.requireNonNull(command, "command");
        commandArgs = List.copyOf(commandArgs);
        targets = List.copyOf(targets);
        argsAfterDoubleDash = List.copyOf(argsAfterDoubleDash);
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        environmentOverrides = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(environmentOverrides, "environmentOverrides")));
        Objects.requireNonNull(inheritance, "inheritance");
    }

    /**
     * The exact argument vector handed to {@link ProcessBuilder}.
     *
     * <p>The {@code --} separator is emitted only when there is something after
     * it. Emitting it unconditionally would change the meaning of the command:
     * for {@code run}, a trailing {@code --} is harmless, but for {@code build}
     * it turns the next word into a negative target pattern position and Bazel
     * reports a different error than the user's command deserves.
     */
    public List<String> toArgv() {
        List<String> argv = new ArrayList<>(
                1 + startupArgs.size() + 1 + commandArgs.size() + targets.size() + argsAfterDoubleDash.size() + 1);
        argv.add(executable.toString());
        argv.addAll(startupArgs);
        if (!command.isEmpty()) {
            argv.add(command);
        }
        argv.addAll(commandArgs);
        argv.addAll(targets);
        if (!argsAfterDoubleDash.isEmpty()) {
            argv.add("--");
            argv.addAll(argsAfterDoubleDash);
        }
        return List.copyOf(argv);
    }

    /** The argv without the executable, which is what the user typed. */
    public List<String> userVisibleArgs() {
        List<String> argv = toArgv();
        return argv.subList(1, argv.size());
    }

    /** True when this command has no Bazel command to run. */
    public boolean isEmpty() {
        return command.isEmpty();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(Path executable, Path workingDirectory) {
        return new Builder(executable, workingDirectory);
    }

    /** Mutable accumulator. Not thread-safe. */
    public static final class Builder {

        private Path executable;
        private List<String> startupArgs = new ArrayList<>();
        private String command = "";
        private List<String> commandArgs = new ArrayList<>();
        private List<String> targets = new ArrayList<>();
        private List<String> argsAfterDoubleDash = new ArrayList<>();
        private Path workingDirectory;
        private Map<String, Optional<String>> environmentOverrides = new LinkedHashMap<>();
        private EnvironmentInheritance inheritance = EnvironmentInheritance.INHERIT_ALL;
        private boolean shellMode;

        private Builder(Path executable, Path workingDirectory) {
            this.executable = executable;
            this.workingDirectory = workingDirectory;
        }

        private Builder(BazelCommand source) {
            this.executable = source.executable;
            this.startupArgs = new ArrayList<>(source.startupArgs);
            this.command = source.command;
            this.commandArgs = new ArrayList<>(source.commandArgs);
            this.targets = new ArrayList<>(source.targets);
            this.argsAfterDoubleDash = new ArrayList<>(source.argsAfterDoubleDash);
            this.workingDirectory = source.workingDirectory;
            this.environmentOverrides = new LinkedHashMap<>(source.environmentOverrides);
            this.inheritance = source.inheritance;
            this.shellMode = source.shellMode;
        }

        public Builder executable(Path value) {
            this.executable = value;
            return this;
        }

        public Builder startupArgs(List<String> value) {
            this.startupArgs = new ArrayList<>(value);
            return this;
        }

        public Builder addStartupArg(String value) {
            this.startupArgs.add(value);
            return this;
        }

        public Builder command(String value) {
            this.command = value;
            return this;
        }

        public Builder commandArgs(List<String> value) {
            this.commandArgs = new ArrayList<>(value);
            return this;
        }

        public Builder addCommandArg(String value) {
            this.commandArgs.add(value);
            return this;
        }

        public Builder targets(List<String> value) {
            this.targets = new ArrayList<>(value);
            return this;
        }

        public Builder argsAfterDoubleDash(List<String> value) {
            this.argsAfterDoubleDash = new ArrayList<>(value);
            return this;
        }

        public Builder workingDirectory(Path value) {
            this.workingDirectory = value;
            return this;
        }

        public Builder environmentOverrides(Map<String, Optional<String>> value) {
            this.environmentOverrides = new LinkedHashMap<>(value);
            return this;
        }

        public Builder setEnvironment(String name, String value) {
            this.environmentOverrides.put(name, Optional.of(value));
            return this;
        }

        /** Records that {@code name} must be absent from the child environment. */
        public Builder unsetEnvironment(String name) {
            this.environmentOverrides.put(name, Optional.empty());
            return this;
        }

        public Builder inheritance(EnvironmentInheritance value) {
            this.inheritance = value;
            return this;
        }

        public Builder shellMode(boolean value) {
            this.shellMode = value;
            return this;
        }

        public BazelCommand build() {
            return new BazelCommand(
                    executable,
                    startupArgs,
                    command,
                    commandArgs,
                    targets,
                    argsAfterDoubleDash,
                    workingDirectory,
                    environmentOverrides,
                    inheritance,
                    shellMode);
        }
    }
}

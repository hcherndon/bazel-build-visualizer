package com.holtherndon.bazelviz.runner.exec;

import com.holtherndon.bazelviz.runner.proc.Subprocess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds a Bazel launcher and works out what it actually is (plan 8.3).
 *
 * <h2>{@code --version}, not {@code version}</h2>
 *
 * <p>The two are not the same command. {@code bazel version} starts or contacts
 * the Bazel server; {@code bazel --version} is answered by the client alone, in
 * about fifteen milliseconds, and touches no server at all. Measured on 6.5.0
 * through 9.2.0.
 *
 * <p>That difference matters more than the extra line of output would.
 * Contacting the server means taking its command lock — so this call would
 * block for the whole of a build the user has running — and, if the startup
 * options differ at all from theirs, it means <em>restarting</em> their server
 * and discarding the analysis cache they were relying on. Identifying a binary
 * is not worth throwing away someone's warm Bazel, so the launcher's own
 * version is recorded only when it volunteers it, and is otherwise left absent.
 *
 * <p>It is still run from the workspace, because that is where Bazelisk reads
 * {@code .bazelversion}: asking from somewhere else can name a different Bazel
 * than the build will use.
 *
 * <h2>Hashing is best effort</h2>
 *
 * <p>Plan 8.3 asks for a file hash "where practical". A Bazelisk launcher is a
 * few megabytes and hashes in milliseconds; some corporate wrappers are shell
 * scripts, and some Bazel installations are hundreds of megabytes. Above the
 * limit the hash is reported absent rather than the resolver stalling a launch
 * to compute one.
 */
public final class BazelExecutableResolver {

    private static final Logger log = LoggerFactory.getLogger(BazelExecutableResolver.class);

    /** Above this, {@link BazelExecutable#sha256()} is left empty. */
    public static final long MAX_HASH_BYTES = 64L * 1024 * 1024;

    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Matches the {@code Build label:} line, which is where Bazel states its own
     * version. Deliberately not the {@code Bazelisk version:} line, and
     * deliberately tolerant of the suffix a release candidate or a fork adds.
     */
    private static final Pattern BUILD_LABEL = Pattern.compile("(?m)^Build label:\\s*(\\S+)\\s*$");

    private static final Pattern BAZELISK_VERSION =
            Pattern.compile("(?m)^Bazelisk version:\\s*(\\S+)\\s*$");

    /** {@code bazel --version} prints this one line and exits without a server. */
    private static final Pattern SHORT_VERSION = Pattern.compile("(?m)^bazel\\s+(\\S+)\\s*$");

    private BazelExecutableResolver() {}

    /**
     * The candidates to offer, in the order plan 8.3 lists them, with
     * duplicates removed.
     *
     * @param explicit a path the user named, if any
     * @param remembered executables used before, most recent first
     */
    public static List<Path> candidates(Optional<Path> explicit, List<Path> remembered) {
        Set<Path> ordered = new LinkedHashSet<>();
        explicit.ifPresent(ordered::add);
        onPath("bazelisk").ifPresent(ordered::add);
        onPath("bazel").ifPresent(ordered::add);
        remembered.stream().filter(Files::isExecutable).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    /** The first candidate that exists and is executable. */
    public static Optional<Path> firstAvailable(Optional<Path> explicit, List<Path> remembered) {
        return candidates(explicit, remembered).stream().filter(Files::isExecutable).findFirst();
    }

    /**
     * Identifies {@code entered} by running it.
     *
     * <p>Run from {@code workspace} when there is one, because that is where
     * Bazelisk reads {@code .bazelversion}: identifying the executable from
     * somewhere else can report a different Bazel than the build will use.
     *
     * @throws ExecutableNotUsableException when the file is missing, is not
     *     executable, or would not report a version
     */
    public static BazelExecutable resolve(String entered, Optional<Path> workspace)
            throws ExecutableNotUsableException {
        return resolve(entered, workspace, Map.of());
    }

    /**
     * Resolves the executable under the environment the build will run with.
     *
     * <p>The environment matters because {@code bazelisk} reads
     * {@code USE_BAZEL_VERSION} from it, so probing with a different one
     * identifies a different Bazel. That is not hypothetical: capability
     * detection keys off the version this returns, and a mismatch makes the
     * planner inject a flag the build then rejects outright — measured, as a
     * Bazel 6.5.0 build failing on {@code --execution_log_compact_file} that
     * the detector had reported as supported because it had probed 9.2.0.
     *
     * @param environment variables the build will run with; the rest of this
     *     process's environment is inherited either way
     */
    public static BazelExecutable resolve(
            String entered, Optional<Path> workspace, Map<String, String> environment)
            throws ExecutableNotUsableException {
        Objects.requireNonNull(entered, "entered");
        Path resolved = resolvePath(entered);
        if (resolved == null) {
            throw new ExecutableNotUsableException(entered, "no such executable on this machine");
        }
        if (!Files.isExecutable(resolved)) {
            throw new ExecutableNotUsableException(entered, resolved + " is not executable");
        }

        Subprocess.Result result;
        try {
            result = Subprocess.run(
                    List.of(resolved.toString(), "--version"),
                    workspace.orElse(null),
                    environment,
                    VERSION_TIMEOUT);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ExecutableNotUsableException(entered, "could not run it: " + failure);
        }

        String output = result.stdout() + (result.stderr().isBlank() ? "" : "\n" + result.stderr());
        Optional<String> version =
                findFirst(SHORT_VERSION, output).or(() -> findFirst(BUILD_LABEL, output));
        if (!result.isSuccess() && version.isEmpty()) {
            throw new ExecutableNotUsableException(entered, result.failureDetail());
        }

        return new BazelExecutable(
                entered,
                resolved,
                output.strip(),
                findFirst(BAZELISK_VERSION, output),
                version,
                sha256(resolved),
                looksLikeBazelisk(resolved, output));
    }

    /**
     * Whether the launcher is Bazelisk.
     *
     * <p>Decided by what it printed first, and only then by its file name. A
     * corporate wrapper named {@code bazel} that delegates to Bazelisk still
     * announces itself in its version output, and the name of a file is the
     * weakest evidence available about what it does.
     */
    private static boolean looksLikeBazelisk(Path resolved, String versionOutput) {
        if (versionOutput.contains("Bazelisk version:")) {
            return true;
        }
        return resolved.getFileName().toString().toLowerCase(Locale.ROOT).contains("bazelisk");
    }

    private static Path resolvePath(String entered) {
        Path asPath = Paths.get(entered);
        if (asPath.isAbsolute() || entered.contains(java.io.File.separator)) {
            return Files.exists(asPath) ? asPath.toAbsolutePath().normalize() : null;
        }
        return onPath(entered).orElse(null);
    }

    private static Optional<Path> onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                candidates.add(Paths.get(entry, name));
            }
        }
        return candidates.stream()
                .filter(Files::isExecutable)
                .map(candidate -> candidate.toAbsolutePath().normalize())
                .findFirst();
    }

    private static Optional<String> findFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Optional<String> sha256(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_HASH_BYTES) {
                return Optional.empty();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append("%02x".formatted(value));
            }
            return Optional.of(hex.toString());
        } catch (IOException | NoSuchAlgorithmException unavailable) {
            log.debug("could not hash {}: {}", file, unavailable.toString());
            return Optional.empty();
        }
    }
}

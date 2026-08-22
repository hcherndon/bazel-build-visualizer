package com.holtherndon.bazelviz.testsupport.bazel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a real Bazel to run tests against, and says plainly when there is none.
 *
 * <p>Project rule 18 requires a real Bazel fixture before an assumption about
 * Bazel is encoded, so the tests that matter most in Phase 2 need an actual
 * binary. They also have to run on a machine that has none. The resolution is
 * that those tests are <em>skipped</em>, never weakened: a test that quietly
 * substitutes a fake Bazel and still passes would report that the real thing
 * works when nobody checked.
 *
 * <p>Search order, most explicit first:
 *
 * <ol>
 *   <li>{@code BBV_TEST_BAZEL} — an explicit path, for CI and for testing a
 *       specific build.
 *   <li>{@code ~/.cache/bbv-dev/bin/bazelisk} — where this repository's own
 *       development setup puts a bazelisk that can provision 6 through 9.
 *   <li>{@code bazelisk} then {@code bazel} on {@code PATH}.
 * </ol>
 *
 * <p>Bazelisk is preferred over Bazel because it can be told which version to
 * run through {@code USE_BAZEL_VERSION}, which is what makes a single test able
 * to cover the 6-to-9 range the project supports.
 */
public final class BazelBinary {

    /** Environment variable naming an explicit Bazel or Bazelisk executable. */
    public static final String OVERRIDE_ENV = "BBV_TEST_BAZEL";

    /** Environment variable Bazelisk reads to choose a Bazel version. */
    public static final String VERSION_ENV = "USE_BAZEL_VERSION";

    private BazelBinary() {}

    /** The executable to test with, or empty when this machine has none. */
    public static Optional<Path> find() {
        String override = System.getenv(OVERRIDE_ENV);
        if (override != null && !override.isBlank()) {
            Path path = Paths.get(override);
            return Files.isExecutable(path) ? Optional.of(path) : Optional.empty();
        }
        Path devBazelisk = Paths.get(System.getProperty("user.home"), ".cache", "bbv-dev", "bin", "bazelisk");
        if (Files.isExecutable(devBazelisk)) {
            return Optional.of(devBazelisk);
        }
        return onPath("bazelisk").or(() -> onPath("bazel"));
    }

    /**
     * Why no Bazel was found, for a skip message. A skipped test should say what
     * would make it run, not merely that it did not.
     */
    public static String whyUnavailable() {
        return "no Bazel found: set " + OVERRIDE_ENV + " to an executable, put bazelisk at "
                + "~/.cache/bbv-dev/bin/bazelisk, or put bazel or bazelisk on PATH";
    }

    /** The versions this project targets, for tests that sweep the range. */
    public static List<String> targetedVersions() {
        return List.of("6.5.0", "7.6.1", "8.4.1", "9.2.0");
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
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }
}

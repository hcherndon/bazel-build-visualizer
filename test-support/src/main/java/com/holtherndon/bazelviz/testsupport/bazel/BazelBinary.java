package com.holtherndon.bazelviz.testsupport.bazel;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds a real Bazel to run tests against, and says plainly when there is none.
 *
 * <p>Project rule 18 requires a real Bazel fixture before an assumption about Bazel is encoded, so
 * the tests that matter most in Phase 2 need an actual binary. They also have to run on a machine
 * that has none. The resolution is that those tests are <em>skipped</em>, never weakened: a test
 * that quietly substitutes a fake Bazel and still passes would report that the real thing works
 * when nobody checked.
 *
 * <p>Search order, most explicit first:
 *
 * <ol>
 *   <li>{@code BBV_TEST_BAZEL} — an explicit path, for CI and for testing a specific build.
 *   <li>{@code ~/.cache/bbv-dev/bin/bazelisk} — where this repository's own development setup puts
 *       a bazelisk that can provision 6 through 9.
 *   <li>{@code bazelisk} then {@code bazel} on {@code PATH}.
 * </ol>
 *
 * <p>Bazelisk is preferred over Bazel because it can be told which version to run through {@code
 * USE_BAZEL_VERSION}, which is what makes a single test able to cover the 6-to-9 range the project
 * supports.
 */
public final class BazelBinary {

  /** Environment variable naming an explicit Bazel or Bazelisk executable. */
  public static final String OVERRIDE_ENV = "BBV_TEST_BAZEL";

  /** Environment variable Bazelisk reads to choose a Bazel version. */
  public static final String VERSION_ENV = "USE_BAZEL_VERSION";

  /** Selects one protocol-fixture release without adding a sweep to ordinary test runs. */
  public static final String REPRODUCIBILITY_VERSION_ENV = "BBV_REPRO_BAZEL_VERSION";

  private BazelBinary() {}

  /**
   * The executable to test with, or empty when this machine has none.
   *
   * @throws IllegalStateException when {@link #OVERRIDE_ENV} is set to something unusable. An
   *     explicit override that cannot be run is a configuration error, not an absent Bazel:
   *     collapsing the two made a typo in the variable skip the entire real-Bazel suite and leave
   *     the build green — hiding exactly the evidence every Phase 2 exit criterion rests on.
   */
  public static Optional<Path> find() {
    String override = System.getenv(OVERRIDE_ENV);
    if (override != null && !override.isBlank()) {
      Path path = Paths.get(override);
      if (!Files.isExecutable(path)) {
        throw new IllegalStateException(
            OVERRIDE_ENV
                + " is set to '"
                + override
                + "', which is not an executable file. Fix it or unset it; it will not"
                + " fall back to PATH, because an explicit choice that cannot be honoured"
                + " must not look like no choice at all.");
      }
      return Optional.of(path);
    }
    Path devBazelisk =
        Paths.get(System.getProperty("user.home"), ".cache", "bbv-dev", "bin", "bazelisk");
    if (Files.isExecutable(devBazelisk)) {
      return Optional.of(devBazelisk);
    }
    return onPath("bazelisk").or(() -> onPath("bazel"));
  }

  /**
   * Why no Bazel was found, for a skip message. A skipped test should say what would make it run,
   * not merely that it did not.
   */
  public static String whyUnavailable() {
    String override = System.getenv(OVERRIDE_ENV);
    if (override != null && !override.isBlank()) {
      return "no Bazel found: " + OVERRIDE_ENV + " names '" + override + "'";
    }
    return "no Bazel found: set "
        + OVERRIDE_ENV
        + " to an executable, put bazelisk at "
        + "~/.cache/bbv-dev/bin/bazelisk, or put bazel or bazelisk on PATH";
  }

  /** The versions this project targets, for tests that sweep the range. */
  public static List<String> targetedVersions() {
    return List.of("6.5.0", "7.6.1", "8.4.1", "9.2.0");
  }

  /**
   * One explicitly selected real-Bazel reproducibility fixture version, defaulting to 9.2.0. Run
   * 7.4.0 and 7.4.1 in separate scoped test invocations, never as an automatic matrix.
   */
  public static String reproducibilityFixtureVersion() {
    String selected = System.getenv(REPRODUCIBILITY_VERSION_ENV);
    if (selected == null || selected.isBlank()) return "9.2.0";
    if (!List.of("9.2.0", "7.4.0", "7.4.1").contains(selected)) {
      throw new IllegalArgumentException(
          REPRODUCIBILITY_VERSION_ENV + " must select exactly one of 9.2.0, 7.4.0, or 7.4.1.");
    }
    return selected;
  }

  private static Optional<Path> onPath(String name) {
    String path = System.getenv("PATH");
    if (path == null) {
      return Optional.empty();
    }
    List<Path> candidates = new ArrayList<>();
    for (String entry : path.split(File.pathSeparator)) {
      if (!entry.isBlank()) {
        candidates.add(Paths.get(entry, name));
      }
    }
    return candidates.stream().filter(Files::isExecutable).findFirst();
  }
}

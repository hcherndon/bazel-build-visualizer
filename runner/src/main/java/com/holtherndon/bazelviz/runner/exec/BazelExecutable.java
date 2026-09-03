package com.holtherndon.bazelviz.runner.exec;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A resolved Bazel launcher and everything known about its identity (plan 8.3).
 *
 * <h2>Why entered and resolved are both kept</h2>
 *
 * <p>The user types {@code bazel}; {@code PATH} resolves it to {@code /opt/homebrew/bin/bazel};
 * that turns out to be Bazelisk, which will download and run some other version entirely depending
 * on {@code .bazelversion} in the workspace. Three different answers to "which Bazel produced this
 * session", and a session that records only one of them cannot be reproduced or compared. So all
 * three are recorded, and {@link #effectiveVersion()} states which one the capability detector
 * actually observed.
 *
 * @param entered exactly what the user or settings supplied
 * @param resolved the absolute file that will be executed
 * @param versionOutput the raw {@code --version} output, kept verbatim because it is the only place
 *     a fork or release-candidate string survives
 * @param launcherVersion the version the launcher itself reports; for Bazelisk this is Bazelisk's
 *     own version, not Bazel's
 * @param bazelVersion the Bazel version that will actually run, when it could be determined; absent
 *     rather than guessed, since Bazelisk resolves it from workspace files this application does
 *     not re-interpret
 * @param sha256 hash of the resolved file when it was practical to compute; a multi-gigabyte
 *     launcher or an unreadable file yields empty, not a fake
 * @param isBazelisk whether the resolved file is a Bazelisk-style launcher
 */
public record BazelExecutable(
    String entered,
    Path resolved,
    String versionOutput,
    Optional<String> launcherVersion,
    Optional<String> bazelVersion,
    Optional<String> sha256,
    boolean isBazelisk) {

  public BazelExecutable {
    Objects.requireNonNull(entered, "entered");
    Objects.requireNonNull(resolved, "resolved");
    Objects.requireNonNull(versionOutput, "versionOutput");
    launcherVersion = Objects.requireNonNull(launcherVersion, "launcherVersion");
    bazelVersion = Objects.requireNonNull(bazelVersion, "bazelVersion");
    sha256 = Objects.requireNonNull(sha256, "sha256");
  }

  /**
   * The version that will run the build: the Bazel version when it is known, otherwise the
   * launcher's own. Never a fabricated value — empty means the session must record "unknown" (plan
   * 11.4).
   */
  public Optional<String> effectiveVersion() {
    return bazelVersion.or(() -> launcherVersion);
  }

  /** Short identity for a UI label, e.g. {@code bazel 7.6.1 (via bazelisk)}. */
  public String displayName() {
    String version = effectiveVersion().orElse("unknown version");
    return isBazelisk ? "bazel " + version + " (via bazelisk)" : "bazel " + version;
  }
}

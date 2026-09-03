package com.holtherndon.bazelviz.ui.capture;

import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import java.util.Objects;

/** Reusable non-secret SSH launch settings for one host and remote repository. */
public record SshConnectionProfile(
    String destination, String port, String workingDirectory, String bazelExecutable) {

  /** Bounds launcher preferences and the saved-connections dropdown. */
  public static final int MAX_SAVED_PROFILES = 20;

  public SshConnectionProfile {
    destination = cleanRequired(destination, "SSH destination");
    port = clean(port);
    workingDirectory = cleanRequired(workingDirectory, "remote working directory");
    bazelExecutable = cleanRequired(bazelExecutable, "remote Bazel executable");
    if (!port.isEmpty()) {
      int parsed;
      try {
        parsed = Integer.parseInt(port);
      } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException("SSH port must be a number in 1..65535", invalid);
      }
      if (parsed < 1 || parsed > 65_535) {
        throw new IllegalArgumentException("SSH port must be in 1..65535");
      }
      SshTarget.of(destination, parsed);
    } else {
      SshTarget.of(destination);
    }
  }

  /** Stable identity: one host may hold several repositories. */
  public String key() {
    return destination + "\u001f" + port + "\u001f" + workingDirectory;
  }

  /** Compact dropdown text that distinguishes ports and repositories. */
  public String displayName() {
    return destination + (port.isEmpty() ? "" : ":" + port) + " — " + workingDirectory;
  }

  private static String cleanRequired(String value, String label) {
    String cleaned = clean(value);
    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException(label + " cannot be blank");
    }
    return cleaned;
  }

  private static String clean(String value) {
    String cleaned = Objects.requireNonNull(value, "value").trim();
    if (cleaned.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("SSH settings cannot contain control characters");
    }
    return cleaned;
  }
}

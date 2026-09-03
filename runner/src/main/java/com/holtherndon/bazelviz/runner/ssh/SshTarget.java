package com.holtherndon.bazelviz.runner.ssh;

import java.util.Objects;
import java.util.OptionalInt;

/** A validated OpenSSH destination. It contains no command-line options. */
public record SshTarget(String destination, OptionalInt port) {

  public SshTarget {
    destination = Objects.requireNonNull(destination, "destination").strip();
    Objects.requireNonNull(port, "port");
    if (destination.isEmpty()) {
      throw new IllegalArgumentException("an SSH destination is required");
    }
    if (destination.startsWith("-") || !destination.matches("[A-Za-z0-9._%+@:\\[\\]-]+")) {
      throw new IllegalArgumentException(
          "SSH destination must be a host or user@host, without options: " + destination);
    }
    int at = destination.indexOf('@');
    if (at >= 0
        && (at == 0
            || at != destination.lastIndexOf('@')
            || destination.substring(0, at).contains(":"))) {
      throw new IllegalArgumentException(
          "SSH destination must not contain credentials or multiple users: " + destination);
    }
    if (port.isPresent() && (port.getAsInt() < 1 || port.getAsInt() > 65_535)) {
      throw new IllegalArgumentException("SSH port must be between 1 and 65535");
    }
  }

  public static SshTarget of(String destination) {
    return new SshTarget(destination, OptionalInt.empty());
  }

  public static SshTarget of(String destination, int port) {
    return new SshTarget(destination, OptionalInt.of(port));
  }

  /** Safe text for UI and manifests; never includes credentials or SSH options. */
  public String displayName() {
    return port.isPresent() ? destination + ":" + port.getAsInt() : destination;
  }
}

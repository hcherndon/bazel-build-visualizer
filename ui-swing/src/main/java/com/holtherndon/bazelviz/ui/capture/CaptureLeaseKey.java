package com.holtherndon.bazelviz.ui.capture;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The canonical execution host and repository protected by one capture lease.
 *
 * <p>The key deliberately contains no command executor or filesystem object. A local caller uses
 * the result of {@link Path#toRealPath(java.nio.file.LinkOption...)}, while an SSH caller uses the
 * configured destination and port together with the canonical remote Linux path returned by its
 * execution filesystem. This lets one application registry compare workspace identities without
 * depending on either transport implementation.
 */
public record CaptureLeaseKey(ExecutionAuthority execution, String canonicalRepositoryRoot) {

  public CaptureLeaseKey {
    execution = Objects.requireNonNull(execution, "execution");
    canonicalRepositoryRoot =
        normalizeRepositoryRoot(
            execution, Objects.requireNonNull(canonicalRepositoryRoot, "canonicalRepositoryRoot"));
  }

  /**
   * Creates a key for an existing local repository.
   *
   * <p>{@code realRepositoryRoot} must be the absolute result of {@code Path.toRealPath()}. The
   * method does no filesystem I/O, so key construction is safe once the caller has resolved the
   * path away from the Swing event thread.
   */
  public static CaptureLeaseKey localRealPath(Path realRepositoryRoot) {
    Objects.requireNonNull(realRepositoryRoot, "realRepositoryRoot");
    if (!realRepositoryRoot.isAbsolute()) {
      throw new IllegalArgumentException("a local repository real path must be absolute");
    }
    return new CaptureLeaseKey(LocalExecution.INSTANCE, realRepositoryRoot.toString());
  }

  /** Creates a key for one canonical repository reached through an SSH configuration. */
  public static CaptureLeaseKey ssh(
      String configuredDestination, OptionalInt configuredPort, String canonicalRemoteRoot) {
    return new CaptureLeaseKey(
        new SshExecution(configuredDestination, configuredPort), canonicalRemoteRoot);
  }

  /** Immutable execution coordinates, independent of a live connection implementation. */
  public sealed interface ExecutionAuthority permits LocalExecution, SshExecution {}

  /** The application process's local execution host. */
  public enum LocalExecution implements ExecutionAuthority {
    INSTANCE
  }

  /** Exact SSH configuration coordinates used to reach a remote execution host. */
  public record SshExecution(String configuredDestination, OptionalInt configuredPort)
      implements ExecutionAuthority {

    public SshExecution {
      configuredDestination = requiredDestination(configuredDestination);
      configuredPort = Objects.requireNonNull(configuredPort, "configuredPort");
      if (configuredPort.isPresent()
          && (configuredPort.getAsInt() < 1 || configuredPort.getAsInt() > 65_535)) {
        throw new IllegalArgumentException("SSH port must be between 1 and 65535");
      }
    }
  }

  private static String normalizeRepositoryRoot(
      ExecutionAuthority execution, String repositoryRoot) {
    if (repositoryRoot.isBlank()) {
      throw new IllegalArgumentException("a canonical repository root is required");
    }
    if (repositoryRoot.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("a canonical repository root cannot contain NUL");
    }
    if (execution instanceof LocalExecution) {
      Path normalized = Path.of(repositoryRoot).normalize();
      if (!normalized.isAbsolute()) {
        throw new IllegalArgumentException("a local repository real path must be absolute");
      }
      return normalized.toString();
    }
    return normalizeRemoteRoot(repositoryRoot);
  }

  private static String normalizeRemoteRoot(String repositoryRoot) {
    if (!repositoryRoot.startsWith("/")) {
      throw new IllegalArgumentException("a canonical remote repository root must be absolute");
    }
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : repositoryRoot.split("/+")) {
      if (part.isEmpty() || part.equals(".")) {
        continue;
      }
      if (part.equals("..")) {
        if (!parts.isEmpty()) {
          parts.removeLast();
        }
      } else {
        parts.addLast(part);
      }
    }
    return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
  }

  private static String requiredDestination(String value) {
    String destination = Objects.requireNonNull(value, "configuredDestination").strip();
    if (destination.isEmpty()) {
      throw new IllegalArgumentException("an SSH destination is required");
    }
    if (destination.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("an SSH destination cannot contain control text");
    }
    return destination;
  }
}

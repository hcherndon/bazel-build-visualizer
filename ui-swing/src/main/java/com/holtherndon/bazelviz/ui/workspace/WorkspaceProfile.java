package com.holtherndon.bazelviz.ui.workspace;

import com.holtherndon.bazelviz.runner.ssh.SshTarget;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One user-managed repository on one execution machine.
 *
 * <p>This is configuration, not a captured build session. In particular, its command and path
 * values are used only after a caller explicitly selects the profile. Merely loading one never
 * starts a process or opens a network connection.
 */
public record WorkspaceProfile(
    String id,
    String label,
    Kind kind,
    Optional<String> destination,
    OptionalInt port,
    String workingDirectory,
    String bazelExecutable,
    OptionalLong lastOpenedMicros) {

  /** Maximum characters accepted in one persisted workspace text field. */
  public static final int MAX_TEXT_CHARACTERS = 16_384;

  /** Newest workspace first, with deterministic ordering for never-opened ties. */
  public static final Comparator<WorkspaceProfile> RECENT_FIRST =
      Comparator.comparingLong(
              (WorkspaceProfile profile) -> profile.lastOpenedMicros().orElse(Long.MIN_VALUE))
          .reversed()
          .thenComparing(WorkspaceProfile::label, String.CASE_INSENSITIVE_ORDER)
          .thenComparing(WorkspaceProfile::id);

  public WorkspaceProfile {
    id = required(id, "workspace id");
    label = required(label, "workspace label");
    kind = Objects.requireNonNull(kind, "kind");
    destination =
        Objects.requireNonNull(destination, "destination")
            .map(value -> checked(value, "SSH destination"))
            .filter(value -> !value.isEmpty());
    port = Objects.requireNonNull(port, "port");
    workingDirectory = required(workingDirectory, "working directory");
    bazelExecutable = required(bazelExecutable, "Bazel executable");
    lastOpenedMicros = Objects.requireNonNull(lastOpenedMicros, "lastOpenedMicros");
    if (lastOpenedMicros.isPresent() && lastOpenedMicros.getAsLong() < 0) {
      throw new IllegalArgumentException("last-opened time must not be negative");
    }
    if (kind == Kind.LOCAL) {
      if (destination.isPresent() || port.isPresent()) {
        throw new IllegalArgumentException(
            "a local workspace cannot carry an SSH destination or port");
      }
    } else {
      String sshDestination =
          destination.orElseThrow(
              () -> new IllegalArgumentException("an SSH workspace needs a destination"));
      new SshTarget(sshDestination, port);
    }
  }

  /** Creates a never-opened local workspace with a new stable identifier. */
  public static WorkspaceProfile local(
      String label, String workingDirectory, String bazelExecutable) {
    return local(newId(), label, workingDirectory, bazelExecutable, OptionalLong.empty());
  }

  /** Creates a local workspace with caller-supplied identity and recency. */
  public static WorkspaceProfile local(
      String id,
      String label,
      String workingDirectory,
      String bazelExecutable,
      OptionalLong lastOpenedMicros) {
    return new WorkspaceProfile(
        id,
        label,
        Kind.LOCAL,
        Optional.empty(),
        OptionalInt.empty(),
        workingDirectory,
        bazelExecutable,
        lastOpenedMicros);
  }

  /** Creates a never-opened SSH workspace with a new stable identifier. */
  public static WorkspaceProfile ssh(
      String label,
      String destination,
      OptionalInt port,
      String workingDirectory,
      String bazelExecutable) {
    return ssh(
        newId(), label, destination, port, workingDirectory, bazelExecutable, OptionalLong.empty());
  }

  /** Creates an SSH workspace with caller-supplied identity and recency. */
  public static WorkspaceProfile ssh(
      String id,
      String label,
      String destination,
      OptionalInt port,
      String workingDirectory,
      String bazelExecutable,
      OptionalLong lastOpenedMicros) {
    return new WorkspaceProfile(
        id,
        label,
        Kind.SSH,
        Optional.of(destination),
        port,
        workingDirectory,
        bazelExecutable,
        lastOpenedMicros);
  }

  /** Returns the same workspace promoted to the supplied recent-open time. */
  public WorkspaceProfile openedAt(long openedMicros) {
    return new WorkspaceProfile(
        id,
        label,
        kind,
        destination,
        port,
        workingDirectory,
        bazelExecutable,
        OptionalLong.of(openedMicros));
  }

  /** Returns the same Workspace with a different Bazel command or executable path. */
  public WorkspaceProfile withBazelExecutable(String executable) {
    return new WorkspaceProfile(
        id, label, kind, destination, port, workingDirectory, executable, lastOpenedMicros);
  }

  /** Stable grouping key for showing several repositories beneath one machine. */
  public String machineKey() {
    return kind == Kind.LOCAL
        ? "local"
        : "ssh\u001f"
            + destination.orElseThrow()
            + "\u001f"
            + (port.isPresent() ? Integer.toString(port.getAsInt()) : "");
  }

  /** Human-readable machine name; contains no credentials beyond the SSH destination. */
  public String machineDisplayName() {
    if (kind == Kind.LOCAL) {
      return "This computer";
    }
    return destination.orElseThrow() + (port.isPresent() ? ":" + port.getAsInt() : "");
  }

  /** Generates a stable identifier for a newly created profile. */
  public static String newId() {
    return UUID.randomUUID().toString();
  }

  private static String required(String value, String label) {
    String checked = checked(value, label);
    if (checked.isEmpty()) {
      throw new IllegalArgumentException(label + " cannot be blank");
    }
    return checked;
  }

  private static String checked(String value, String label) {
    String checked = Objects.requireNonNull(value, label).strip();
    if (checked.length() > MAX_TEXT_CHARACTERS) {
      throw new IllegalArgumentException(label + " exceeds " + MAX_TEXT_CHARACTERS + " characters");
    }
    if (checked.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(label + " cannot contain control characters");
    }
    return checked;
  }

  /** Where this repository is executed. */
  public enum Kind {
    LOCAL("This computer"),
    SSH("SSH host");

    private final String displayName;

    Kind(String displayName) {
      this.displayName = displayName;
    }

    @Override
    public String toString() {
      return displayName;
    }
  }
}

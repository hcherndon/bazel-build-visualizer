package com.holtherndon.bazelviz.runner.plan;

import com.holtherndon.bazelviz.core.source.DataSource;
import com.holtherndon.bazelviz.runner.caps.Capability;
import com.holtherndon.bazelviz.runner.caps.CapabilityStatus;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One flag the planner wants to add, with everything plan 4.3 says the user must be shown before it
 * is added.
 *
 * <p>ADR-007 makes instrumentation transparent, and this record is the shape of that promise: eight
 * fields, one per bullet in plan 4.3's "added items" list. A field left out here becomes a fact the
 * dialog cannot show, so the record is deliberately wide rather than convenient.
 *
 * @param argv the exact argument, already in {@code --flag=value} form, ready to place in the
 *     command; never shell-quoted (plan 8.4)
 * @param placement whether it is a startup option or a command option — the same text in the wrong
 *     position is a different flag or an error
 * @param capability what this flag turns on
 * @param capabilityStatus what the selected binary said about it; a flag whose status is not {@link
 *     CapabilityStatus#SUPPORTED} is shown and explained but never injected
 * @param reason one sentence the user can read, in their terms, not ours
 * @param enables the capture source this makes available, so the dialog can say what is lost by
 *     vetoing it
 * @param overhead relative cost
 * @param writesFile the local file it creates, absent when it writes none; always session-local and
 *     absolute when present (plan 8.4)
 * @param mayContainSensitiveData whether the file it writes can carry absolute paths, command lines
 *     or environment values (plan 22.2)
 * @param userCanDisable false only for the flags without which there is no session at all
 */
public record AddedFlag(
    String argv,
    Placement placement,
    Capability capability,
    CapabilityStatus capabilityStatus,
    String reason,
    DataSource enables,
    Overhead overhead,
    Optional<Path> writesFile,
    boolean mayContainSensitiveData,
    boolean userCanDisable) {

  /** Where in the argv a flag belongs. */
  public enum Placement {
    /**
     * Before the Bazel command. Changing a startup option restarts the Bazel server, discarding its
     * analysis cache — which is why the planner never adds one for instrumentation, and why this
     * constant exists mainly so a user-supplied startup option can be described.
     */
    STARTUP,
    /** After the Bazel command; where all injected instrumentation goes. */
    COMMAND
  }

  public AddedFlag {
    Objects.requireNonNull(argv, "argv");
    Objects.requireNonNull(placement, "placement");
    Objects.requireNonNull(capability, "capability");
    Objects.requireNonNull(capabilityStatus, "capabilityStatus");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(enables, "enables");
    Objects.requireNonNull(overhead, "overhead");
    writesFile = Objects.requireNonNull(writesFile, "writesFile");
    if (!argv.startsWith("--")) {
      throw new IllegalArgumentException("an injected flag must start with --, got " + argv);
    }
  }

  /** True when this flag will actually appear in the effective command. */
  public boolean isApplied() {
    return capabilityStatus.isSupported();
  }
}

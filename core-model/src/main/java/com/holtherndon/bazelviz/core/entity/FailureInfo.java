package com.holtherndon.bazelviz.core.entity;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Why something failed.
 *
 * <h2>The category comes from the structure, never from the message text</h2>
 *
 * <p>Bazel's {@code FailureDetail} is designed so a consumer built against a stale proto can still
 * classify a failure: the set oneof field names the category and its field number 1 names the
 * subcategory. Both are read positionally, through descriptors, so a category added by a future
 * Bazel is reported by name rather than dropped.
 *
 * <p>The alternative — parsing {@link #message} — was measured changing wording between versions,
 * and the proto says outright that the text is not intended to be used algorithmically. It is
 * stored verbatim for the user and never matched against.
 *
 * <h2>The exit code the user cares about is not the action's</h2>
 *
 * <p>Measured on all four supported versions: {@code ActionExecuted.exit_code} was 1 for every
 * failure regardless of what the process actually returned — a command exiting 7 and a command
 * exiting 3 both reported 1. The real code is {@code failureDetail.spawn.spawn_exit_code}, and that
 * is what {@link #spawnExitCode} carries.
 *
 * @param category the oneof field's name, e.g. {@code spawn}
 * @param subcategory the enum constant at field 1 of the category submessage, e.g. {@code
 *     NON_ZERO_EXIT}; empty when the submessage has no such field
 * @param message Bazel's own text, verbatim and never parsed
 * @param spawnExitCode the process's exit code, when the failure was a spawn that reported one
 */
public record FailureInfo(
    Optional<String> category,
    Optional<String> subcategory,
    String message,
    OptionalInt spawnExitCode) {

  public FailureInfo {
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(subcategory, "subcategory");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(spawnExitCode, "spawnExitCode");
  }

  /** The category and subcategory as one label, or the empty string. */
  public String describeCategory() {
    if (category.isEmpty()) {
      return "";
    }
    return subcategory.map(sub -> category.get() + "/" + sub).orElseGet(category::get);
  }
}

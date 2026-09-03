package com.holtherndon.bazelviz.storage.entities;

import com.holtherndon.bazelviz.storage.events.RawLocation;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One thing that went wrong, from whichever of the three places records it.
 *
 * <p>The three are genuinely different and the view says which is which. An action failed and Bazel
 * said why. A target failed to build. Or a target was never attempted, which under {@code
 * --nokeep_going} says more about a sibling's failure than about this target.
 *
 * @param kind which of the three
 * @param subject the label, or the action's primary output when there is no label
 * @param detail the failure category or the abort reason
 * @param message Bazel's own text, verbatim and never parsed. Empty for a {@link Kind#OUTPUT} row,
 *     whose text is too large to carry per row and lives in the journal instead — see {@code
 *     rawLocation}
 * @param rawLocation where this row's verbatim event bytes are, when the row is one whose text has
 *     to be read back out of the journal. Empty for the rows that carry their own text, which is
 *     every kind but {@link Kind#OUTPUT}: a row that already has its message has no reason to send
 *     a reader to disk for it
 */
public record ErrorRow(
    Kind kind,
    long id,
    String subject,
    Optional<String> detail,
    Optional<String> message,
    OptionalLong bepEventId,
    Optional<RawLocation> rawLocation) {

  /**
   * A row whose text it already carries — an action, a target, an abort.
   *
   * <p>The six-argument shape the three {@code ErrorQueries} readers have always used. It names no
   * journal address because those three queries ask for none: their message column is the message.
   */
  public ErrorRow(
      Kind kind,
      long id,
      String subject,
      Optional<String> detail,
      Optional<String> message,
      OptionalLong bepEventId) {
    this(kind, id, subject, detail, message, bepEventId, Optional.empty());
  }

  /** Where a failure came from. */
  public enum Kind {
    /** An action ran and failed. The message names the command. */
    ACTION("Action failed"),

    /** A target did not build. */
    TARGET("Target failed"),

    /**
     * A target was not attempted. Under {@code --nokeep_going} a single failure aborts every
     * sibling, so these can outnumber the real failures by thousands and are summarized rather than
     * listed.
     */
    NOT_BUILT("Not built"),

    /**
     * Console output Bazel wrote while the build failed.
     *
     * <p>For most failures this is the only diagnostic there is. A syntax error produces thirteen
     * events and zero structured messages: the compiler's own text exists solely in {@code
     * progress.stderr}, and a Errors view without these rows shows nothing at all for the most
     * common kind of failure a build has.
     *
     * <p>The row carries the size and the journal address, not the text — the bytes stay in the
     * journal (ADR-004). Selecting the row is what reads them back: the inspector fetches the
     * payload named by {@link ErrorRow#rawLocation()}, decodes it, and shows the stderr it holds.
     * The Message column stays a pointer, because copying thousands of console bytes into every row
     * is the duplication ADR-004 exists to refuse.
     */
    OUTPUT("Build output");

    private final String title;

    Kind(String title) {
      this.title = title;
    }

    public String title() {
      return title;
    }
  }

  public ErrorRow {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(bepEventId, "bepEventId");
    Objects.requireNonNull(rawLocation, "rawLocation");
  }
}

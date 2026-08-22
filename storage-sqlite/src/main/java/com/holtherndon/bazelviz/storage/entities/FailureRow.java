package com.holtherndon.bazelviz.storage.entities;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One thing that went wrong, from whichever of the three places records it.
 *
 * <p>The three are genuinely different and the view says which is which. An
 * action failed and Bazel said why. A target failed to build. Or a target was
 * never attempted, which under {@code --nokeep_going} says more about a
 * sibling's failure than about this target.
 *
 * @param kind which of the three
 * @param subject the label, or the action's primary output when there is no
 *     label
 * @param detail the failure category or the abort reason
 * @param message Bazel's own text, verbatim and never parsed
 */
public record FailureRow(
        Kind kind,
        long id,
        String subject,
        Optional<String> detail,
        Optional<String> message,
        OptionalLong bepEventId) {

    /** Where a failure came from. */
    public enum Kind {
        /** An action ran and failed. The message names the command. */
        ACTION("Action failed"),

        /** A target did not build. */
        TARGET("Target failed"),

        /**
         * A target was not attempted. Under {@code --nokeep_going} a single
         * failure aborts every sibling, so these can outnumber the real
         * failures by thousands and are summarized rather than listed.
         */
        NOT_BUILT("Not built");

        private final String title;

        Kind(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public FailureRow {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(message, "message");
    }
}

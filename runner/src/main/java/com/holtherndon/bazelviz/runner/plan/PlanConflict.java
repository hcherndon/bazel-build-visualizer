package com.holtherndon.bazelviz.runner.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Something about the user's command that the planner will not decide on its own (plan 4.3
 * "conflicts").
 *
 * <h2>Mandatory conflicts block the launch</h2>
 *
 * <p>Plan 4.3 ends with "do not launch until mandatory conflicts are resolved", and {@link
 * #mandatory} is that flag. The rule exists because every mandatory conflict here is a case where
 * guessing would either destroy the user's data or silently produce a session that is not a
 * recording of the command they asked for. Replacing a corporate BES backend without asking would
 * divert build results away from the system their team relies on; overwriting an existing {@code
 * --build_event_binary_file} would delete a file the user pointed at.
 *
 * <p>Advisory conflicts are things the user should see but that have a safe default: a later option
 * overriding an earlier one, for example, is Bazel's documented behavior and the plan asks only
 * that it be made visible.
 *
 * @param kind what sort of conflict this is
 * @param mandatory true when the launch must not proceed until it is resolved
 * @param summary one line naming the problem
 * @param detail the specifics: which flag, which path, which value
 * @param resolutions the choices offered, in the order they should be shown; the first is the
 *     recommended one
 * @param offendingArgv the user's argument that caused it, when there is one
 */
public record PlanConflict(
    Kind kind,
    boolean mandatory,
    String summary,
    String detail,
    List<Resolution> resolutions,
    Optional<String> offendingArgv) {

  /** The conflict cases enumerated by plan 4.3. */
  public enum Kind {
    /** The command already names another BES backend (plan 8.5). */
    EXISTING_BES_BACKEND,
    /** The command already writes a BEP file where this one would. */
    EXISTING_BEP_OUTPUT,
    /** The user turned off action publication that the preset wants on. */
    ACTION_PUBLICATION_DISABLED,
    /** The selected binary does not accept a flag the preset asked for. */
    UNSUPPORTED_FLAG,
    /** A file the plan would write already exists. */
    DESTINATION_EXISTS,
    /** The Bazel command cannot carry action enrichment at all. */
    COMMAND_NOT_INSTRUMENTABLE,
    /** A user option would be overridden by a later one on the same line. */
    LATER_OPTION_WINS
  }

  /**
   * One way out of a conflict.
   *
   * @param id stable identifier the UI hands back to the planner; not display text
   * @param label what the button says
   * @param consequence what the user gives up by choosing it — never empty, because a choice
   *     presented without its cost is not an informed one
   */
  public record Resolution(String id, String label, String consequence) {
    public Resolution {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(label, "label");
      Objects.requireNonNull(consequence, "consequence");
      if (consequence.isBlank()) {
        throw new IllegalArgumentException("resolution " + id + " must state its consequence");
      }
    }
  }

  /** The three choices plan 8.5 requires for an upstream BES backend. */
  public static final String RESOLUTION_REPLACE_BES = "replace-bes-backend";

  public static final String RESOLUTION_KEEP_BES_USE_FILE = "keep-bes-use-binary-file";
  public static final String RESOLUTION_CANCEL = "cancel-and-edit";

  public PlanConflict {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(detail, "detail");
    resolutions = List.copyOf(resolutions);
    offendingArgv = Objects.requireNonNull(offendingArgv, "offendingArgv");
    if (mandatory && resolutions.isEmpty()) {
      throw new IllegalArgumentException(
          "mandatory conflict " + kind + " blocks the launch but offers no way to resolve it");
    }
  }

  /** The resolution with this id, if it is one of the offered choices. */
  public Optional<Resolution> resolution(String id) {
    return resolutions.stream().filter(r -> r.id().equals(id)).findFirst();
  }
}

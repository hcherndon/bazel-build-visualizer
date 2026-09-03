package com.holtherndon.bazelviz.core.redact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What to redact, and what to leave alone.
 *
 * <h2>Two policies, because display and export are different decisions</h2>
 *
 * <p>The UI masks a handful of well-known fields so a shoulder-surfer does not read a token off the
 * screen, and leaves everything else alone because the person at the keyboard already has the
 * session on their disk. An export is the opposite situation: the bytes are about to leave the
 * machine, so redaction is mandatory and covers absolute paths as well as secrets, since a path
 * carries the account name of whoever ran the build.
 *
 * <h2>Path prefixes are mapped, not deleted</h2>
 *
 * <p>{@code /Users/someone/code/project/bazel-out/darwin_arm64-fastbuild/bin/a.o} has one
 * identifying part and one informative part. Replacing the whole thing with a placeholder would
 * leave an export in which no two paths can be told apart; replacing the prefix leaves {@code
 * [workspace]/bazel-out/darwin_arm64-fastbuild/bin/a.o}, which is still a path a reader can reason
 * about. {@link #pathPrefixes} is the map from absolute prefix to placeholder, longest match first.
 *
 * @param redactAbsolutePaths map known prefixes and mask the user component of any other absolute
 *     path
 * @param omitEnvironmentValues drop every environment value while keeping its name (plan 22.2's
 *     "optional omission of environment values while retaining names"), rather than only those a
 *     rule recognises
 * @param redactLabels replace target labels and package names with pseudonyms. Off even for export:
 *     it hides internal project structure at the cost of making the export nearly unreadable, so it
 *     is a decision a person makes rather than a default they discover.
 */
public record RedactionPolicy(
    List<SecretPattern> patterns,
    boolean redactAbsolutePaths,
    boolean omitEnvironmentValues,
    boolean redactLabels,
    Map<String, String> pathPrefixes) {

  public RedactionPolicy {
    patterns = List.copyOf(patterns);
    pathPrefixes = Map.copyOf(pathPrefixes);
  }

  /**
   * The mandatory export policy: secrets and absolute paths.
   *
   * <p>docs/privacy.md: "Export (sharing a session or a report) runs redaction mandatorily". There
   * is deliberately no way to build an export policy with secret redaction switched off.
   */
  public static RedactionPolicy forExport() {
    return new RedactionPolicy(
        SecretPatterns.defaults(),
        /* redactAbsolutePaths= */ true,
        /* omitEnvironmentValues= */ false,
        /* redactLabels= */ false,
        Map.of());
  }

  /**
   * What the UI masks by default: secrets only.
   *
   * <p>Paths stay: the user is looking at their own build on their own machine, and a view that
   * showed them {@code [workspace]/...} instead of the path they could paste into a terminal would
   * be worse at the job the view exists for.
   */
  public static RedactionPolicy forDisplay() {
    return new RedactionPolicy(
        SecretPatterns.defaults(),
        /* redactAbsolutePaths= */ false,
        /* omitEnvironmentValues= */ false,
        /* redactLabels= */ false,
        Map.of());
  }

  /** The same policy with one more prefix mapped. */
  public RedactionPolicy withPathPrefix(String absolutePrefix, String placeholder) {
    Objects.requireNonNull(absolutePrefix, "absolutePrefix");
    Objects.requireNonNull(placeholder, "placeholder");
    if (absolutePrefix.isBlank()) {
      return this;
    }
    Map<String, String> prefixes = new LinkedHashMap<>(pathPrefixes);
    prefixes.put(absolutePrefix, placeholder);
    return new RedactionPolicy(
        patterns, redactAbsolutePaths, omitEnvironmentValues, redactLabels, prefixes);
  }

  /** The same policy with environment values omitted rather than inspected. */
  public RedactionPolicy omittingEnvironmentValues() {
    return new RedactionPolicy(patterns, redactAbsolutePaths, true, redactLabels, pathPrefixes);
  }

  /** The same policy with labels pseudonymised. See {@link #redactLabels}. */
  public RedactionPolicy redactingLabels() {
    return new RedactionPolicy(
        patterns, redactAbsolutePaths, omitEnvironmentValues, true, pathPrefixes);
  }

  /** The same policy with user-supplied name globs added. */
  public RedactionPolicy withUserPatterns(List<String> nameGlobs) {
    return new RedactionPolicy(
        SecretPatterns.withUserPatterns(nameGlobs),
        redactAbsolutePaths,
        omitEnvironmentValues,
        redactLabels,
        pathPrefixes);
  }

  /** One sentence describing what this policy will do, for a confirmation. */
  public String describe() {
    StringBuilder text =
        new StringBuilder("Secrets matching ")
            .append(patterns.size())
            .append(" patterns are replaced with pseudonyms");
    if (redactAbsolutePaths) {
      text.append("; absolute paths have their identifying prefix mapped");
    }
    if (omitEnvironmentValues) {
      text.append("; every environment value is omitted and only its name kept");
    }
    if (redactLabels) {
      text.append(
          "; target labels and package names are pseudonymised, which makes the"
              + " export much harder to read");
    }
    return text.append('.').toString();
  }
}

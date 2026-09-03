package com.holtherndon.bazelviz.enrich.execlog;

import com.holtherndon.bazelviz.core.enrich.EnrichmentCommand.EnvVar;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Withholds environment values whose names suggest a secret.
 *
 * <h2>Why by name and not by value</h2>
 *
 * <p>Plan 22.2 requires default patterns covering token, password, credential and key names,
 * user-editable. Detecting secrets by inspecting values means guessing, and guessing wrong in the
 * direction that leaks. A name-based rule is conservative in the safe direction: it hides some
 * things that were not secret, and the user can see it did.
 *
 * <p>A withheld value is not the same as an unset one, which is why {@link EnvVar} carries a flag
 * rather than just an empty value. {@code PATH=""} and {@code API_TOKEN=<withheld>} are different
 * facts about a build and a user diagnosing "why did this action behave differently" needs to tell
 * them apart.
 */
public final class EnvironmentRedactor {

  /**
   * The default patterns of plan 22.2.
   *
   * <p>Matched against the upper-cased name as a substring, so {@code AWS_SECRET_ACCESS_KEY},
   * {@code npm_token} and {@code MY_PASSWORD_FILE} are all caught. {@code KEY} deliberately also
   * catches {@code SSH_KEY_PATH}, which is a path and not a secret — erring that way is the point.
   */
  public static final List<String> DEFAULT_PATTERNS =
      List.of(
          "TOKEN",
          "PASSWORD",
          "PASSWD",
          "SECRET",
          "CREDENTIAL",
          "KEY",
          "AUTH",
          "SESSION",
          "COOKIE",
          "PRIVATE");

  private final List<Pattern> patterns;

  /** A redactor using {@link #DEFAULT_PATTERNS}. */
  public EnvironmentRedactor() {
    this(DEFAULT_PATTERNS);
  }

  /**
   * A redactor using {@code substrings}, upper-cased and matched literally.
   *
   * @param substrings user-supplied names or fragments; an empty list redacts nothing, which is a
   *     choice the user is allowed to make
   */
  public EnvironmentRedactor(List<String> substrings) {
    this.patterns =
        substrings.stream()
            .map(fragment -> Pattern.compile(Pattern.quote(fragment.toUpperCase(Locale.ROOT))))
            .toList();
  }

  /** A redactor that withholds nothing, for tests and for local-only sessions. */
  public static EnvironmentRedactor none() {
    return new EnvironmentRedactor(List.of());
  }

  /** The variable as it should be stored. */
  public EnvVar apply(String name, String value) {
    return shouldRedact(name)
        ? new EnvVar(name, Optional.empty(), true)
        : new EnvVar(name, Optional.ofNullable(value), false);
  }

  /** True when this name's value is withheld. */
  public boolean shouldRedact(String name) {
    String upper = name.toUpperCase(Locale.ROOT);
    return patterns.stream().anyMatch(pattern -> pattern.matcher(upper).find());
  }
}

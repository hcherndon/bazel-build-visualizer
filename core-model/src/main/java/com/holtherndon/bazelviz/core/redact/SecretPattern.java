package com.holtherndon.bazelviz.core.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One rule for recognising something that must not leave this machine.
 *
 * <h2>Two kinds, because they answer different questions</h2>
 *
 * <p>A {@link Kind#NAME} rule looks at what a value is <em>called</em> — {@code GITHUB_TOKEN},
 * {@code --remote_header}, {@code AWS_SECRET_ACCESS_KEY} — and redacts the value whatever it looks
 * like. A {@link Kind#VALUE} rule looks at the value itself, for shapes that are secret wherever
 * they appear: a bearer token, a URL with a password in it, a PEM block.
 *
 * <p>Both are needed and neither is sufficient. A name rule cannot catch a credential passed as a
 * positional argument, and a value rule cannot catch a password that happens to look like an
 * ordinary word.
 *
 * <h2>Name patterns are globs, not regular expressions</h2>
 *
 * <p>Plan 22.2 requires the pattern list to be user-editable, and a user-supplied regular
 * expression is a denial-of-service risk in a matcher that runs once per argument over a build with
 * five million actions — catastrophic backtracking is a real property of ordinary-looking patterns.
 * So a user writes {@code *_TOKEN} and this compiles it to an anchored regex with no backtracking
 * in it. The value rules, which do need real expressions, are built in and fixed.
 */
public record SecretPattern(String name, Kind kind, Pattern pattern, String description) {

  public SecretPattern {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(description, "description");
  }

  /** What a rule inspects. */
  public enum Kind {
    /** The name a value is stored under. */
    NAME,
    /** The value itself. */
    VALUE
  }

  /**
   * A rule matching names against a glob.
   *
   * <p>{@code *} matches any run of characters and every other character is literal. Matching is
   * case-insensitive, because environment variables are conventionally upper case and flags are
   * not, and a rule that missed {@code github_token} while catching {@code GITHUB_TOKEN} would be a
   * rule that works on the examples and not on the data.
   */
  public static SecretPattern named(String glob, String description) {
    return new SecretPattern(glob, Kind.NAME, compileGlob(glob), description);
  }

  /** A built-in rule matching a value shape. Not constructible from user input. */
  static SecretPattern valued(String name, String regex, String description) {
    return new SecretPattern(
        name, Kind.VALUE, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), description);
  }

  /**
   * Compiles a glob to an anchored, backtracking-free regular expression.
   *
   * <p>Every literal run is quoted, so a glob containing {@code .} or {@code (} means those
   * characters rather than a regex construct. The only metacharacter is {@code *}, which becomes
   * {@code .*} — and because the expression is a flat alternation-free sequence, it cannot
   * backtrack catastrophically however long the input is.
   */
  private static Pattern compileGlob(String glob) {
    if (glob.isBlank()) {
      throw new IllegalArgumentException("a pattern must match something");
    }
    StringBuilder regex = new StringBuilder();
    StringBuilder literal = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char character = glob.charAt(i);
      if (character == '*') {
        if (!literal.isEmpty()) {
          regex.append(Pattern.quote(literal.toString()));
          literal.setLength(0);
        }
        regex.append(".*");
      } else {
        literal.append(character);
      }
    }
    if (!literal.isEmpty()) {
      regex.append(Pattern.quote(literal.toString()));
    }
    return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
  }

  /** True when this rule recognises {@code text}. */
  public boolean matches(String text) {
    if (text == null) {
      return false;
    }
    Matcher matcher = pattern.matcher(text);
    return kind == Kind.NAME ? matcher.matches() : matcher.find();
  }

  /**
   * The regions of {@code text} this rule recognises, in order.
   *
   * <p>Only meaningful for a {@link Kind#VALUE} rule: a name rule condemns the whole value rather
   * than part of it.
   */
  public List<int[]> regionsIn(String text) {
    if (kind == Kind.NAME || text == null) {
      return List.of();
    }
    List<int[]> regions = new ArrayList<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      // Group 1, when a rule declares one, is the secret inside a larger
      // match: the password in a URL rather than the whole URL, so the
      // host stays readable.
      int group = matcher.groupCount() >= 1 && matcher.start(1) >= 0 ? 1 : 0;
      regions.add(new int[] {matcher.start(group), matcher.end(group)});
    }
    return List.copyOf(regions);
  }

  /** The rule's name, as it appears in a report. */
  public String displayName() {
    return name.toLowerCase(Locale.ROOT);
  }
}

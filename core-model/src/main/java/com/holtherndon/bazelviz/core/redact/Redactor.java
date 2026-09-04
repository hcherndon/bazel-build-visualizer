package com.holtherndon.bazelviz.core.redact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies a {@link RedactionPolicy} and records what it did.
 *
 * <h2>Pseudonyms, not asterisks</h2>
 *
 * <p>A redaction that replaced every secret with the same {@code ****} would destroy the one thing
 * a reader most needs from a shared session: whether the token in these ten thousand actions is
 * <em>the same</em> token. So each distinct value gets a stable pseudonym — {@code
 * [redacted:9f2c1a7b04]} — and the same value redacts to the same pseudonym everywhere in one
 * export.
 *
 * <p>The pseudonym is a truncated digest of the value under a random key generated per {@link
 * Redactor} and never written anywhere. That matters: a bare digest of a low-entropy secret is a
 * password hash, and a password hash in a file being sent to a colleague is the secret with an
 * extra step. Keying it makes the pseudonym meaningless outside this one export, which is exactly
 * the lifetime it needs.
 *
 * <h2>Paths keep their shape</h2>
 *
 * <p>An absolute path has an identifying prefix and an informative remainder. The prefix is mapped
 * — to {@code [workspace]}, {@code [output-base]}, {@code [home]} — and the remainder survives, so
 * an export still shows which directory a file came from without showing whose machine it was on. A
 * path under no known prefix has its user component masked, because {@code /Users/<name>} is the
 * account name of whoever ran the build.
 *
 * <h2>Not thread-safe</h2>
 *
 * <p>One export, one redactor, one thread. The pseudonym table has to be shared across the whole
 * pass or the same secret would get different names in different tables, so there is nothing to
 * gain from making it concurrent.
 */
public final class Redactor {

  /** What a redacted value is replaced with, before the pseudonym. */
  private static final String PREFIX = "[redacted:";

  /** Hex characters of digest in a pseudonym. 40 bits, extended on collision. */
  private static final int PSEUDONYM_LENGTH = 10;

  private final RedactionPolicy policy;
  private final byte[] key;
  private final RedactionReport report = new RedactionReport();
  private final Map<String, String> pseudonyms = new HashMap<>();
  private final Set<String> usedPseudonyms = new HashSet<>();
  private final List<Map.Entry<String, String>> prefixes;

  public Redactor(RedactionPolicy policy) {
    this(policy, randomKey());
  }

  /**
   * @param key the pseudonym key; a parameter so a test can assert that the same value redacts to
   *     the same name, which is otherwise unobservable by design
   */
  public Redactor(RedactionPolicy policy, byte[] key) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.key = key.clone();
    // Longest prefix first, so /home/me/project is mapped as the workspace
    // rather than as the home directory it happens to sit under.
    this.prefixes =
        policy.pathPrefixes().entrySet().stream()
            .sorted(
                (left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .map(entry -> Map.<String, String>entry(entry.getKey(), entry.getValue()))
            .toList();
  }

  private static byte[] randomKey() {
    byte[] bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  /** What this redactor has done so far. */
  public RedactionReport report() {
    return report;
  }

  /**
   * Redacts one command-line argument.
   *
   * <p>Handles {@code --flag=value} by checking the flag against the name rules and redacting only
   * the value, so an export still shows that {@code --remote_header} was passed while not showing
   * what it carried. A bare argument is checked against the value rules alone.
   */
  public String argument(String argument, String field) {
    if (argument == null) {
      return null;
    }
    report.countInspected();
    int equals = argument.indexOf('=');
    if (equals > 0) {
      String flag = argument.substring(0, equals);
      String value = argument.substring(equals + 1);
      SecretPattern rule = matchingNameRule(flag);
      if (rule != null) {
        return flag + "=" + pseudonymFor(value, rule, field);
      }
      return flag + "=" + redactArgumentValue(value, field);
    }
    SecretPattern rule = matchingNameRule(argument);
    if (rule != null) {
      // A flag whose *name* is a secret pattern and which carries no
      // value here: the secret is the next argument, which the caller
      // sees separately. Nothing to do but leave the flag readable.
      return argument;
    }
    return redactArgumentValue(argument, field);
  }

  /**
   * Redacts a whole argv, carrying the "secret follows this flag" rule across the boundary between
   * one argument and the next.
   */
  public List<String> argv(List<String> argv, String field) {
    Objects.requireNonNull(argv, "argv");
    List<String> out = new ArrayList<>(argv.size());
    // The rule that matched the previous argument, when that argument was a
    // flag naming a secret and carried no "=value" of its own. Bazel
    // accepts both --remote_header=X and --remote_header X, and an export
    // that redacted only the first shape would leak the second.
    SecretPattern pending = null;
    for (String argument : argv) {
      if (pending != null && argument != null) {
        report.countInspected();
        out.add(pseudonymFor(argument, pending, field));
        pending = null;
        continue;
      }
      out.add(argument(argument, field));
      pending = argument != null && argument.indexOf('=') < 0 ? matchingNameRule(argument) : null;
    }
    return List.copyOf(out);
  }

  /**
   * Redacts one environment value.
   *
   * <p>The name is never redacted: knowing that {@code GITHUB_TOKEN} was set is diagnostic and
   * knowing its value is not.
   */
  public String environmentValue(String name, String value, String field) {
    if (value == null) {
      return null;
    }
    report.countInspected();
    if (policy.omitEnvironmentValues()) {
      report.record(
          "omit-environment-values", field, "[omitted]", "every environment value, by policy");
      return "[omitted]";
    }
    SecretPattern rule = matchingNameRule(name);
    if (rule != null) {
      return pseudonymFor(value, rule, field);
    }
    return redactValue(value, field);
  }

  /**
   * Maps an absolute path's identifying prefix.
   *
   * <p>A relative path is returned unchanged: it carries no account name and is the same on every
   * machine, which is the whole reason Bazel uses them.
   */
  public String path(String path, String field) {
    if (path == null || !policy.redactAbsolutePaths()) {
      return path;
    }
    report.countInspected();
    String current = redactValue(path, field);
    for (Map.Entry<String, String> prefix : prefixes) {
      if (current.startsWith(prefix.getKey())) {
        report.record("path-prefix", field, prefix.getValue(), "a known absolute prefix");
        return prefix.getValue() + current.substring(prefix.getKey().length());
      }
    }
    String masked = maskUserComponent(current);
    if (!masked.equals(current)) {
      report.record("home-directory", field, "[user]", "the account name in a home-directory path");
    }
    return masked;
  }

  /**
   * Replaces a value even when it does not match a pattern.
   *
   * <p>Some provenance is identifying by definition rather than by shape. An SSH destination must
   * not survive merely because it did not look like a token.
   */
  public String pseudonymize(String value, String field, String description) {
    if (value == null) {
      return null;
    }
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(description, "description");
    report.countInspected();
    return pseudonymFor(value, "forced-pseudonym", description, field);
  }

  /**
   * Redacts free text — a failure message, a progress line, a description.
   *
   * <p>Bazel's own failure text routinely embeds a whole command line and a sandbox path, so this
   * runs the value rules and the path mapping over the text rather than treating it as opaque.
   */
  public String text(String text, String field) {
    if (text == null) {
      return null;
    }
    report.countInspected();
    String withoutSecrets = redactValue(text, field);
    return policy.redactAbsolutePaths() ? mapPrefixesWithin(withoutSecrets, field) : withoutSecrets;
  }

  /**
   * A target label, pseudonymised only when the policy says so.
   *
   * <p>Off by default even for export. A label is how every other number in the session is
   * identified, so redacting it costs most of the export's usefulness — a trade a person should
   * make deliberately.
   */
  public String label(String label, String field) {
    if (label == null || !policy.redactLabels()) {
      return label;
    }
    report.countInspected();
    return pseudonymFor(label, SecretPattern.named("*", "a target label"), field);
  }

  // --- internals --------------------------------------------------------

  private SecretPattern matchingNameRule(String name) {
    if (name == null) {
      return null;
    }
    for (SecretPattern pattern : policy.patterns()) {
      if (pattern.kind() == SecretPattern.Kind.NAME && pattern.matches(name)) {
        return pattern;
      }
    }
    return null;
  }

  /** Applies every value rule, replacing only the regions each one claims. */
  private String redactValue(String value, String field) {
    String current = value;
    for (SecretPattern pattern : policy.patterns()) {
      if (pattern.kind() != SecretPattern.Kind.VALUE) {
        continue;
      }
      List<int[]> regions = pattern.regionsIn(current);
      if (regions.isEmpty()) {
        continue;
      }
      StringBuilder rebuilt = new StringBuilder();
      int cursor = 0;
      for (int[] region : regions) {
        rebuilt.append(current, cursor, region[0]);
        rebuilt.append(pseudonymFor(current.substring(region[0], region[1]), pattern, field));
        cursor = region[1];
      }
      rebuilt.append(current, cursor, current.length());
      current = rebuilt.toString();
    }
    return current;
  }

  /** Applies both secret and path policy to one argv value. */
  private String redactArgumentValue(String value, String field) {
    String current = redactValue(value, field);
    if (!policy.redactAbsolutePaths()) {
      return current;
    }
    current = mapPrefixesWithin(current, field);
    String masked = maskUserComponent(current);
    if (!masked.equals(current)) {
      report.record("home-directory", field, "[user]", "the account name in a home-directory path");
    }
    return masked;
  }

  /** Maps every known prefix wherever it appears inside a longer string. */
  private String mapPrefixesWithin(String text, String field) {
    String current = text;
    for (Map.Entry<String, String> prefix : prefixes) {
      if (current.contains(prefix.getKey())) {
        report.record(
            "path-prefix", field, prefix.getValue(), "a known absolute prefix inside a message");
        current = current.replace(prefix.getKey(), prefix.getValue());
      }
    }
    return current;
  }

  /**
   * Replaces the account name in a home-directory path.
   *
   * <p>Only the two shapes that carry one: macOS {@code /Users/<name>} and Linux {@code
   * /home/<name>}. Guessing more widely would mangle ordinary paths, and a path this does not
   * recognise is still covered by whichever prefix the caller mapped.
   */
  private static String maskUserComponent(String path) {
    for (String root : new String[] {"/Users/", "/home/"}) {
      if (!path.startsWith(root)) {
        continue;
      }
      int end = path.indexOf('/', root.length());
      if (end < 0) {
        return root + "[user]";
      }
      return root + "[user]" + path.substring(end);
    }
    return path;
  }

  /** The stable pseudonym for one value, minting one on first sight. */
  private String pseudonymFor(String value, SecretPattern rule, String field) {
    return pseudonymFor(value, rule.displayName(), rule.description(), field);
  }

  private String pseudonymFor(String value, String rule, String description, String field) {
    String existing = pseudonyms.get(value);
    if (existing == null) {
      existing = mint(value);
      pseudonyms.put(value, existing);
      report.countDistinctSecret();
    }
    report.record(rule, field, existing, description);
    return existing;
  }

  private String mint(String value) {
    String digest = keyedDigest(value);
    for (int length = PSEUDONYM_LENGTH; length <= digest.length(); length += 4) {
      String candidate = PREFIX + digest.substring(0, length) + "]";
      if (usedPseudonyms.add(candidate)) {
        return candidate;
      }
    }
    // Every truncation collided, which needs a full digest to be wrong.
    String candidate = PREFIX + digest + "]";
    usedPseudonyms.add(candidate);
    return candidate;
  }

  private String keyedDigest(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(key);
      digest.update(value.getBytes(StandardCharsets.UTF_8));
      byte[] bytes = digest.digest();
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte value2 : bytes) {
        hex.append(String.format(Locale.ROOT, "%02x", value2));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is required of every Java platform.
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}

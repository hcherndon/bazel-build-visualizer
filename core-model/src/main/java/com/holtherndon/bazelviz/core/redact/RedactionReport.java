package com.holtherndon.bazelviz.core.redact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a redaction pass actually changed.
 *
 * <h2>Why this is a required output and not a log line</h2>
 *
 * <p>docs/privacy.md: an export "shows the user exactly what was redacted before anything is
 * written". Pattern matching finds what it was told to look for, so the honest thing an export can
 * offer is not "everything sensitive was removed" but "here is what was removed, and here is how
 * much of the session it touched" — and then let a person decide. That decision needs a report, not
 * a reassurance.
 *
 * <h2>Counted by rule and by field</h2>
 *
 * <p>By rule, because a rule that fired ten thousand times is either doing its job or is far too
 * broad, and the count is what tells the two apart. By field, because "1,200 redactions" is not
 * actionable and "1,200 redactions, all in {@code actions.command_line}" is.
 *
 * <p>Not thread-safe: one pass, one thread, one report.
 */
public final class RedactionReport {

  private final Map<String, Long> byRule = new LinkedHashMap<>();
  private final Map<String, Long> byField = new LinkedHashMap<>();
  private final Map<String, String> examples = new LinkedHashMap<>();
  private long distinctSecrets;
  private long valuesInspected;

  /** Records one redaction. */
  void record(String rule, String field, String pseudonym, String description) {
    byRule.merge(rule, 1L, Long::sum);
    byField.merge(field, 1L, Long::sum);
    // The pseudonym and the rule's description, never the value. An
    // "example" that quoted what was found would put the secret in the
    // report that exists so the secret does not travel.
    examples.putIfAbsent(rule, description + ", shown as " + pseudonym);
  }

  void countInspected() {
    valuesInspected++;
  }

  void countDistinctSecret() {
    distinctSecrets++;
  }

  /** How many values were examined, redacted or not. */
  public long valuesInspected() {
    return valuesInspected;
  }

  /** How many redactions were made in total. */
  public long redactions() {
    return byRule.values().stream().mapToLong(Long::longValue).sum();
  }

  /**
   * How many distinct secret values were found.
   *
   * <p>Distinct from {@link #redactions()} and usually much smaller: one token passed to every
   * action is one secret and ten thousand redactions. Both numbers are worth seeing, and the ratio
   * between them is what tells a reader whether a single credential is leaking everywhere or many
   * different ones are.
   */
  public long distinctSecrets() {
    return distinctSecrets;
  }

  /** Redactions per rule, in the order the rules first fired. */
  public Map<String, Long> byRule() {
    return Map.copyOf(byRule);
  }

  /** Redactions per field, in the order the fields were first touched. */
  public Map<String, Long> byField() {
    return Map.copyOf(byField);
  }

  /** True when nothing matched, which is a result and not a failure. */
  public boolean isEmpty() {
    return byRule.isEmpty();
  }

  /**
   * The lines a confirmation dialog shows before anything is written.
   *
   * <p>Deliberately includes the "nothing matched" case in full. An export that found no secrets
   * and said nothing would be indistinguishable from one where redaction never ran.
   */
  public List<String> lines() {
    List<String> lines = new ArrayList<>();
    if (byRule.isEmpty()) {
      lines.add(
          "No pattern matched anything in "
              + valuesInspected
              + " inspected values. That is a result of the patterns that ran, not a"
              + " guarantee that the session holds no secrets.");
      return List.copyOf(lines);
    }
    lines.add(
        redactions()
            + " redactions across "
            + distinctSecrets
            + (distinctSecrets == 1 ? " distinct value" : " distinct values")
            + ", from "
            + valuesInspected
            + " values inspected.");
    for (Map.Entry<String, Long> entry : byRule.entrySet()) {
      lines.add(
          "  "
              + entry.getKey()
              + ": "
              + entry.getValue()
              + " — "
              + examples.getOrDefault(entry.getKey(), "matched"));
    }
    for (Map.Entry<String, Long> entry : byField.entrySet()) {
      lines.add("  in " + entry.getKey() + ": " + entry.getValue());
    }
    lines.add(
        "Pattern matching finds what it was told to look for. Read this list before"
            + " sharing the export.");
    return List.copyOf(lines);
  }

  /** Folds another pass's counts into this one. */
  public void merge(RedactionReport other) {
    Objects.requireNonNull(other, "other");
    other.byRule.forEach((rule, count) -> byRule.merge(rule, count, Long::sum));
    other.byField.forEach((field, count) -> byField.merge(field, count, Long::sum));
    other.examples.forEach(examples::putIfAbsent);
    distinctSecrets += other.distinctSecrets;
    valuesInspected += other.valuesInspected;
  }

  @Override
  public String toString() {
    return String.join("\n", lines());
  }
}

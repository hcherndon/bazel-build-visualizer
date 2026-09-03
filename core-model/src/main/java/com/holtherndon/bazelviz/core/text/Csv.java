package com.holtherndon.bazelviz.core.text;

import java.util.List;

/**
 * RFC 4180 field quoting, in one place.
 *
 * <h2>Why this is shared rather than repeated</h2>
 *
 * <p>Three exports write CSV — the graph, the tables, and the findings — and a quoting rule copied
 * three times is a rule that is right in three places until somebody fixes a bug in one of them.
 * The rule is four lines, which is exactly the size at which duplication looks harmless and stops
 * being checked.
 *
 * <h2>The escape is doubling, not backslashes</h2>
 *
 * <p>RFC 4180 escapes a quote by doubling it, and every spreadsheet reads that. A backslash escape
 * is what a programmer expects and what Excel renders literally, which is the sort of difference
 * that is discovered by a colleague rather than by a test.
 */
public final class Csv {

  private Csv() {}

  /**
   * One field, quoted only when it has to be.
   *
   * <p>Quoting everything would also be valid and is what many writers do; not quoting what needs
   * no quotes keeps an exported action table readable in a terminal, which is where most of them
   * are first looked at.
   */
  public static String field(String value) {
    if (value == null) {
      return "";
    }
    if (value.indexOf(',') < 0
        && value.indexOf('"') < 0
        && value.indexOf('\n') < 0
        && value.indexOf('\r') < 0) {
      return value;
    }
    return '"' + value.replace("\"", "\"\"") + '"';
  }

  /** A whole row, comma-separated, without a line terminator. */
  public static String row(List<String> values) {
    StringBuilder line = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        line.append(',');
      }
      line.append(field(values.get(i)));
    }
    return line.toString();
  }
}

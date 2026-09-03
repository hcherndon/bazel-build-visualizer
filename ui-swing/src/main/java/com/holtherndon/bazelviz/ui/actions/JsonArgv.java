package com.holtherndon.bazelviz.ui.actions;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads back the JSON array an action's command line is stored as.
 *
 * <p>A dozen lines rather than a JSON binding because the shape is fixed — an array of strings,
 * written by this application's own writer — and because the cost of being wrong is a command line
 * rendered oddly rather than a value misread. Anything it cannot parse comes back empty, and the
 * caller shows the stored text verbatim instead of guessing.
 */
final class JsonArgv {

  private JsonArgv() {}

  /** The arguments, or an empty list when {@code json} is not what it should be. */
  static List<String> parse(String json) {
    String trimmed = json == null ? "" : json.trim();
    if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || !trimmed.endsWith("]")) {
      return List.of();
    }
    List<String> arguments = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inString = false;
    for (int i = 1; i < trimmed.length() - 1; i++) {
      char c = trimmed.charAt(i);
      if (!inString) {
        if (c == '"') {
          inString = true;
          current.setLength(0);
        } else if (c != ',' && !Character.isWhitespace(c)) {
          return List.of();
        }
        continue;
      }
      if (c == '\\') {
        if (++i >= trimmed.length() - 1) {
          return List.of();
        }
        char escaped = trimmed.charAt(i);
        switch (escaped) {
          case '"' -> current.append('"');
          case '\\' -> current.append('\\');
          case 'b' -> current.append('\b');
          case 'f' -> current.append('\f');
          case 'n' -> current.append('\n');
          case 'r' -> current.append('\r');
          case 't' -> current.append('\t');
          case 'u' -> {
            if (i + 4 >= trimmed.length() - 1) {
              return List.of();
            }
            current.append((char) Integer.parseInt(trimmed.substring(i + 1, i + 5), 16));
            i += 4;
          }
          default -> {
            return List.of();
          }
        }
        continue;
      }
      if (c == '"') {
        inString = false;
        arguments.add(current.toString());
        continue;
      }
      current.append(c);
    }
    return inString ? List.of() : List.copyOf(arguments);
  }
}

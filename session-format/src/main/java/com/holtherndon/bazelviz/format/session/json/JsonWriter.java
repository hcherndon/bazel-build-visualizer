package com.holtherndon.bazelviz.format.session.json;

import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonBool;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNull;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNumber;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonString;
import java.io.IOException;
import java.util.Map;

/**
 * Serializes a {@link JsonValue} back to text.
 *
 * <p>Pretty output is the default for the files this module owns. The manifest is meant to be
 * readable with {@code cat} while debugging a broken session, and a readable diff between two
 * revisions of a session directory is worth the handful of extra bytes.
 *
 * <p>Number literals are emitted verbatim, so a value parsed from a document written by a newer
 * build survives the round trip unchanged.
 */
public final class JsonWriter {

  private static final String INDENT = "  ";

  private JsonWriter() {}

  /** Pretty-printed, two-space indented, with a trailing newline. */
  public static String writePretty(JsonValue value) {
    StringBuilder out = new StringBuilder();
    try {
      write(out, value, true);
      out.append('\n');
    } catch (IOException e) {
      throw new AssertionError("StringBuilder does not throw", e);
    }
    return out.toString();
  }

  /** Single-line output, no insignificant whitespace. */
  public static String writeCompact(JsonValue value) {
    StringBuilder out = new StringBuilder();
    try {
      write(out, value, false);
    } catch (IOException e) {
      throw new AssertionError("StringBuilder does not throw", e);
    }
    return out.toString();
  }

  public static void write(Appendable out, JsonValue value, boolean pretty) throws IOException {
    writeValue(out, value, pretty, 0);
  }

  private static void writeValue(Appendable out, JsonValue value, boolean pretty, int depth)
      throws IOException {
    switch (value) {
      case JsonNull ignored -> out.append("null");
      case JsonBool b -> out.append(b.value() ? "true" : "false");
      case JsonNumber n -> out.append(n.literal());
      case JsonString s -> writeString(out, s.value());
      case JsonArray a -> writeArray(out, a, pretty, depth);
      case JsonObject o -> writeObject(out, o, pretty, depth);
    }
  }

  private static void writeArray(Appendable out, JsonArray array, boolean pretty, int depth)
      throws IOException {
    if (array.elements().isEmpty()) {
      out.append("[]");
      return;
    }
    out.append('[');
    boolean first = true;
    for (JsonValue element : array.elements()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, pretty, depth + 1);
      writeValue(out, element, pretty, depth + 1);
    }
    newline(out, pretty, depth);
    out.append(']');
  }

  private static void writeObject(Appendable out, JsonObject object, boolean pretty, int depth)
      throws IOException {
    if (object.members().isEmpty()) {
      out.append("{}");
      return;
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, JsonValue> member : object.members().entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, pretty, depth + 1);
      writeString(out, member.getKey());
      out.append(':');
      if (pretty) {
        out.append(' ');
      }
      writeValue(out, member.getValue(), pretty, depth + 1);
    }
    newline(out, pretty, depth);
    out.append('}');
  }

  private static void newline(Appendable out, boolean pretty, int depth) throws IOException {
    if (!pretty) {
      return;
    }
    out.append('\n');
    for (int i = 0; i < depth; i++) {
      out.append(INDENT);
    }
  }

  private static void writeString(Appendable out, String value) throws IOException {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append("\\u%04x".formatted((int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}

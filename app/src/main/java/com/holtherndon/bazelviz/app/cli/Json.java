package com.holtherndon.bazelviz.app.cli;

import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonArray;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonNull;
import com.holtherndon.bazelviz.format.session.json.JsonValue.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A small ordered-object builder over {@code session-format}'s {@link JsonValue}. The writer and
 * reader there are already the project's tested JSON implementation, and the manifest is written
 * with them, so the {@code --json} output of this tool is produced by exactly the same code that
 * produces the session's own files rather than by a second string-concatenating emitter that would
 * eventually disagree about escaping.
 *
 * <p>Every {@code put} of an {@code Optional} writes JSON {@code null} when the value is absent. A
 * machine reader must be able to tell "not known" from "zero" just as a human reader can (plan
 * 11.4).
 */
final class Json {

  private Json() {}

  static Obj object() {
    return new Obj();
  }

  static JsonArray array(List<? extends JsonValue> elements) {
    return new JsonArray(List.copyOf(elements));
  }

  static JsonArray strings(List<String> values) {
    return JsonArray.ofStrings(values);
  }

  /** An ordered JSON object under construction. */
  static final class Obj {

    private final Map<String, JsonValue> members = new LinkedHashMap<>();

    private Obj() {}

    Obj put(String key, JsonValue value) {
      members.put(key, value == null ? JsonNull.INSTANCE : value);
      return this;
    }

    Obj put(String key, String value) {
      return put(key, JsonValue.of(value));
    }

    Obj put(String key, long value) {
      return put(key, JsonValue.of(value));
    }

    Obj put(String key, int value) {
      return put(key, JsonValue.of((long) value));
    }

    Obj put(String key, boolean value) {
      return put(key, JsonValue.of(value));
    }

    Obj put(String key, OptionalLong value) {
      return put(key, value.isPresent() ? JsonValue.of(value.getAsLong()) : JsonNull.INSTANCE);
    }

    Obj put(String key, OptionalInt value) {
      return put(
          key, value.isPresent() ? JsonValue.of((long) value.getAsInt()) : JsonNull.INSTANCE);
    }

    Obj putString(String key, Optional<String> value) {
      return put(key, value.<JsonValue>map(JsonValue::of).orElse(JsonNull.INSTANCE));
    }

    Obj putObjects(String key, List<JsonObject> values) {
      List<JsonValue> elements = new ArrayList<>(values);
      return put(key, new JsonArray(elements));
    }

    Obj putStrings(String key, List<String> values) {
      return put(key, JsonArray.ofStrings(values));
    }

    JsonObject build() {
      return new JsonObject(members);
    }
  }
}

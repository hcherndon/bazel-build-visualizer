package com.holtherndon.bazelviz.format.session.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A minimal, immutable JSON document model.
 *
 * <p>This exists so {@code session-format} can read and write its own small metadata files (the
 * manifest today) without pulling a third-party JSON binding into the dependency graph, and — more
 * importantly — so <em>unknown</em> members survive a read-modify-write cycle intact. A binding
 * that maps JSON straight onto a record silently discards anything the record does not declare;
 * that would mean a session written by a newer build loses data the moment an older build touches
 * it (plan 21.5).
 *
 * <p>Numbers keep their original literal text. Re-emitting the literal rather than a reparsed
 * {@code double} means a value this build does not understand is written back byte-for-byte, and no
 * precision is invented or lost on the way through.
 *
 * <p>Object members preserve insertion order, so a rewritten document keeps the shape a human
 * reader last saw it in.
 */
public sealed interface JsonValue {

  /** JSON {@code null}. Distinct from "member absent" — see {@link JsonObject#member}. */
  record JsonNull() implements JsonValue {
    public static final JsonNull INSTANCE = new JsonNull();
  }

  /** JSON {@code true} / {@code false}. */
  record JsonBool(boolean value) implements JsonValue {
    public static final JsonBool TRUE = new JsonBool(true);
    public static final JsonBool FALSE = new JsonBool(false);
  }

  /**
   * A JSON number, retained as its source literal.
   *
   * <p>The literal is authoritative. {@link #asLong()} and {@link #asDouble()} are interpretations
   * of it and are only called for fields this build understands.
   */
  record JsonNumber(String literal) implements JsonValue {
    public JsonNumber {
      Objects.requireNonNull(literal, "literal");
      if (literal.isEmpty()) {
        throw new IllegalArgumentException("number literal must not be empty");
      }
    }

    public static JsonNumber of(long value) {
      return new JsonNumber(Long.toString(value));
    }

    public static JsonNumber of(double value) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw new IllegalArgumentException("JSON has no representation for " + value);
      }
      return new JsonNumber(Double.toString(value));
    }

    /**
     * @throws JsonException when the literal is not an exact 64-bit integer
     */
    public long asLong() {
      try {
        return Long.parseLong(literal);
      } catch (NumberFormatException e) {
        throw new JsonException("expected an integer, found " + literal, e);
      }
    }

    /**
     * @throws JsonException when the literal is not an exact 32-bit integer
     */
    public int asInt() {
      long value = asLong();
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new JsonException("value " + value + " does not fit in 32 bits");
      }
      return (int) value;
    }

    public double asDouble() {
      try {
        return Double.parseDouble(literal);
      } catch (NumberFormatException e) {
        throw new JsonException("expected a number, found " + literal, e);
      }
    }
  }

  /** A JSON string. */
  record JsonString(String value) implements JsonValue {
    public JsonString {
      Objects.requireNonNull(value, "value");
    }
  }

  /** A JSON array. The element list is defensively copied and unmodifiable. */
  record JsonArray(List<JsonValue> elements) implements JsonValue {
    public JsonArray {
      elements = List.copyOf(elements);
    }

    public static JsonArray of(List<JsonValue> elements) {
      return new JsonArray(elements);
    }

    public static JsonArray ofStrings(List<String> values) {
      List<JsonValue> elements = new ArrayList<>(values.size());
      for (String value : values) {
        elements.add(new JsonString(value));
      }
      return new JsonArray(elements);
    }
  }

  /**
   * A JSON object. Member order is preserved; the map is unmodifiable.
   *
   * <p>{@link #member(String)} deliberately returns empty for both an absent key and an explicit
   * {@code null}: for this application's purposes "the writer had nothing to say" and "the writer
   * said nothing is known" are the same statement, and neither may become {@code 0} or {@code ""}
   * (plan 11.4).
   */
  record JsonObject(Map<String, JsonValue> members) implements JsonValue {
    public JsonObject {
      members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public static JsonObject empty() {
      return new JsonObject(Map.of());
    }

    /** True when the key is present, even if its value is JSON {@code null}. */
    public boolean hasKey(String key) {
      return members.containsKey(key);
    }

    /** The member at {@code key}, empty when absent <em>or</em> JSON {@code null}. */
    public Optional<JsonValue> member(String key) {
      JsonValue value = members.get(key);
      return value == null || value instanceof JsonNull ? Optional.empty() : Optional.of(value);
    }
  }

  static JsonValue of(String value) {
    return value == null ? JsonNull.INSTANCE : new JsonString(value);
  }

  static JsonValue of(long value) {
    return JsonNumber.of(value);
  }

  static JsonValue of(boolean value) {
    return value ? JsonBool.TRUE : JsonBool.FALSE;
  }
}

package com.holtherndon.bazelviz.ui.table;

import com.holtherndon.bazelviz.format.session.json.JsonReader;
import com.holtherndon.bazelviz.format.session.json.JsonValue;
import com.holtherndon.bazelviz.format.session.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One table's column presentation, as a value: which columns are where, how wide each one is, which
 * are hidden, and what the user last sorted by.
 *
 * <p>This is what {@link TableHeaderInteractions} snapshots and what {@link ColumnStateStore}
 * writes to disk. It generalizes the read-before- reapply width preservation {@code EventsView}
 * grew for its live refresh: the same three facts a {@code JTable.setModel} discards — width,
 * order, visibility — captured once, reapplied after every swap, and now persisted across restarts
 * as well.
 *
 * <p>Hidden is not dropped: a hidden column keeps its id in {@link #order} and its width in {@link
 * #widths}, so re-showing it restores both. Visibility is presentation only — the table model
 * underneath never changes shape.
 *
 * <p>The sort {@link Sort#key() key} is an opaque string the view's adapter interprets. It is
 * usually a column id, but a view whose toolbar offers orderings with no column of their own (the
 * Actions tab's start-time sort) stores those here too, so the whole sort choice survives a
 * restart, not just the column-shaped part of it.
 */
public record ColumnState(
    List<String> order, Map<String, Integer> widths, Set<String> hidden, Optional<Sort> sort) {

  /** The persisted sort choice: an adapter-interpreted key and a direction. */
  public record Sort(String key, boolean descending) {
    public Sort {
      Objects.requireNonNull(key, "key");
    }
  }

  public ColumnState {
    order = List.copyOf(order);
    widths = Collections.unmodifiableMap(new LinkedHashMap<>(widths));
    hidden = Collections.unmodifiableSet(new LinkedHashSet<>(hidden));
    Objects.requireNonNull(sort, "sort");
  }

  /** No recorded preferences: every view falls back to its own defaults. */
  public static ColumnState empty() {
    return new ColumnState(List.of(), Map.of(), Set.of(), Optional.empty());
  }

  public boolean isEmpty() {
    return order.isEmpty() && widths.isEmpty() && hidden.isEmpty() && sort.isEmpty();
  }

  /** This state as a JSON document, ending in a newline. */
  public String toJson() {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put("order", JsonValue.JsonArray.ofStrings(order));
    Map<String, JsonValue> widthMembers = new LinkedHashMap<>();
    widths.forEach((id, width) -> widthMembers.put(id, JsonValue.JsonNumber.of(width)));
    members.put("widths", new JsonValue.JsonObject(widthMembers));
    members.put("hidden", JsonValue.JsonArray.ofStrings(List.copyOf(hidden)));
    sort.ifPresent(
        chosen -> {
          Map<String, JsonValue> sortMembers = new LinkedHashMap<>();
          sortMembers.put("key", new JsonValue.JsonString(chosen.key()));
          sortMembers.put("descending", JsonValue.of(chosen.descending()));
          members.put("sort", new JsonValue.JsonObject(sortMembers));
        });
    return JsonWriter.writePretty(new JsonValue.JsonObject(members)) + "\n";
  }

  /**
   * Parses {@code text}, taking every member it recognizes and ignoring the rest. A document that
   * is not JSON at all throws; a document that is JSON but not this shape degrades member by member
   * — a future build adding a member must not cost this one the members it understands.
   */
  public static ColumnState fromJson(String text) {
    JsonValue parsed = JsonReader.parse(text);
    if (!(parsed instanceof JsonValue.JsonObject object)) {
      return empty();
    }
    List<String> order = strings(object, "order");
    Map<String, Integer> widths = new LinkedHashMap<>();
    object
        .member("widths")
        .filter(value -> value instanceof JsonValue.JsonObject)
        .map(value -> (JsonValue.JsonObject) value)
        .ifPresent(
            widthObject ->
                widthObject
                    .members()
                    .forEach(
                        (id, value) -> {
                          if (value instanceof JsonValue.JsonNumber number) {
                            try {
                              int width = number.asInt();
                              if (width > 0) {
                                widths.put(id, width);
                              }
                            } catch (RuntimeException notAnInt) {
                              // A width that is not a positive int is ignored,
                              // not invented.
                            }
                          }
                        }));
    Set<String> hidden = new LinkedHashSet<>(strings(object, "hidden"));
    Optional<Sort> sort =
        object
            .member("sort")
            .filter(value -> value instanceof JsonValue.JsonObject)
            .map(value -> (JsonValue.JsonObject) value)
            .flatMap(
                sortObject ->
                    sortObject
                        .member("key")
                        .filter(value -> value instanceof JsonValue.JsonString)
                        .map(value -> ((JsonValue.JsonString) value).value())
                        .map(
                            key ->
                                new Sort(
                                    key,
                                    sortObject
                                        .member("descending")
                                        .map(
                                            value ->
                                                value instanceof JsonValue.JsonBool bool
                                                    && bool.value())
                                        .orElse(false))));
    return new ColumnState(order, widths, hidden, sort);
  }

  private static List<String> strings(JsonValue.JsonObject object, String key) {
    List<String> values = new ArrayList<>();
    object
        .member(key)
        .filter(value -> value instanceof JsonValue.JsonArray)
        .map(value -> (JsonValue.JsonArray) value)
        .ifPresent(
            array -> {
              for (JsonValue element : array.elements()) {
                if (element instanceof JsonValue.JsonString string) {
                  values.add(string.value());
                }
              }
            });
    return values;
  }
}

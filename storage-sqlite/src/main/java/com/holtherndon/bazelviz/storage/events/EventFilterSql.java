package com.holtherndon.bazelviz.storage.events;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Compiles allowlisted event fields and operators into SQL with bound values. */
final class EventFilterSql {

  private enum Kind {
    NUMBER,
    TEXT,
    BOOLEAN
  }

  private record Field(String sql, Kind kind) {}

  private static final Map<String, Field> FIELDS =
      Map.ofEntries(
          Map.entry("id", new Field("e.id", Kind.NUMBER)),
          Map.entry("sequence", new Field("e.sequence", Kind.NUMBER)),
          Map.entry("type", new Field(readable("e.event_type"), Kind.NUMBER)),
          Map.entry("children", new Field(readable("e.child_count"), Kind.NUMBER)),
          Map.entry("decode", new Field("e.decode_status", Kind.TEXT)),
          Map.entry("event_id", new Field("i.display", Kind.TEXT)),
          Map.entry("raw_bytes", new Field("e.raw_length", Kind.NUMBER)),
          Map.entry("event_time", new Field("e.event_micros", Kind.NUMBER)),
          Map.entry("received", new Field("e.receive_micros", Kind.NUMBER)),
          Map.entry("stream", new Field("e.stream_id", Kind.NUMBER)),
          Map.entry("unknown_fields", new Field(readable("e.has_unknown_fields"), Kind.BOOLEAN)),
          Map.entry("last_message", new Field(readable("e.last_message"), Kind.BOOLEAN)));

  private EventFilterSql() {}

  private static String readable(String column) {
    return "CASE WHEN e.decode_status IN ('OK', 'UNKNOWN_FIELDS') THEN " + column + " END";
  }

  record Predicate(String sql, List<Object> values) {
    Predicate {
      values = List.copyOf(values);
    }

    int bind(PreparedStatement statement, int start) throws SQLException {
      for (Object value : values) {
        statement.setObject(start++, value);
      }
      return start;
    }
  }

  static Predicate compile(FilterExpression expression) {
    List<Object> values = new ArrayList<>();
    return new Predicate(compile(expression, values), values);
  }

  private static String compile(FilterExpression expression, List<Object> values) {
    if (expression.isEmpty()) {
      return "1";
    }
    if (expression instanceof FilterExpression.Group group) {
      List<String> parts = new ArrayList<>();
      for (FilterExpression child : group.children()) {
        if (!child.isEmpty()) {
          parts.add(compile(child, values));
        }
      }
      return "("
          + String.join(group.junction() == FilterExpression.Junction.ALL ? " AND " : " OR ", parts)
          + ")";
    }
    Condition condition = (Condition) expression;
    Field field = FIELDS.get(condition.field());
    if (field == null) {
      throw new IllegalArgumentException("Unknown event filter field: " + condition.field());
    }
    Operator operator = condition.operator();
    String column = "(" + field.sql() + ")";
    if (operator == Operator.IS_PRESENT || operator == Operator.IS_ABSENT) {
      return column + (operator == Operator.IS_PRESENT ? " IS NOT NULL" : " IS NULL");
    }
    for (String value : condition.values()) {
      values.add(
          switch (field.kind()) {
            case TEXT -> value;
            case NUMBER -> Long.parseLong(value);
            case BOOLEAN -> {
              if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("Boolean filters require true or false.");
              }
              yield value.equals("true") ? 1 : 0;
            }
          });
    }
    return switch (operator) {
      case EQUALS -> column + " = ?";
      case NOT_EQUALS -> column + " <> ?";
      case IN, NOT_IN ->
          column
              + (operator == Operator.IN ? " IN (" : " NOT IN (")
              + String.join(",", Collections.nCopies(condition.values().size(), "?"))
              + ")";
      case CONTAINS, NOT_CONTAINS -> {
        requireKind(field, Kind.TEXT);
        yield "instr(lower("
            + column
            + "), lower(?)) "
            + (operator == Operator.CONTAINS ? "> 0" : "= 0");
      }
      case GREATER_THAN, AT_LEAST, LESS_THAN, AT_MOST -> {
        requireKind(field, Kind.NUMBER);
        String comparison =
            switch (operator) {
              case GREATER_THAN -> ">";
              case AT_LEAST -> ">=";
              case LESS_THAN -> "<";
              case AT_MOST -> "<=";
              default -> throw new IllegalStateException();
            };
        yield column + " " + comparison + " ?";
      }
      default -> throw new IllegalArgumentException("Unsupported filter operator: " + operator);
    };
  }

  private static void requireKind(Field field, Kind kind) {
    if (field.kind() != kind) {
      throw new IllegalArgumentException("The filter operator does not support this field.");
    }
  }
}

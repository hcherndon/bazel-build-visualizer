package com.holtherndon.bazelviz.enrich.repro;

import com.holtherndon.bazelviz.core.filter.FilterExpression;
import com.holtherndon.bazelviz.core.filter.RegexFilter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.sqlite.Function;

/** Private allowlisted mapping of the common visual filter to comparison rows. */
final class ComparisonFilter {
  private ComparisonFilter() {}

  record Predicate(String sql, List<Object> values) {}

  static void register(Connection connection) throws SQLException {
    Function.create(
        connection,
        "repro_regex",
        new Function() {
          @Override
          protected void xFunc() throws SQLException {
            try {
              result(RegexFilter.matches(value_text(0), value_text(1)) ? 1 : 0);
            } catch (IllegalArgumentException e) {
              error("Regex was invalid or exceeded its work limit.");
            }
          }
        });
  }

  static Predicate compile(FilterExpression expression) {
    List<Object> values = new ArrayList<>();
    return new Predicate(sql(expression, values), List.copyOf(values));
  }

  private static String sql(FilterExpression expression, List<Object> values) {
    if (expression.isEmpty()) {
      return "1";
    }
    if (expression instanceof FilterExpression.Group group) {
      List<String> parts = new ArrayList<>();
      for (FilterExpression child : group.children()) {
        if (!child.isEmpty()) {
          parts.add(sql(child, values));
        }
      }
      return "("
          + String.join(group.junction() == FilterExpression.Junction.ALL ? " AND " : " OR ", parts)
          + ")";
    }
    var condition = (FilterExpression.Condition) expression;
    String field = condition.field();
    if (!Set.of("target", "mnemonic", "output", "finding").contains(field)) {
      throw new IllegalArgumentException("Unknown comparison filter field.");
    }
    String value = condition.values().isEmpty() ? "" : condition.values().getFirst();
    return switch (condition.operator()) {
      case IS_PRESENT -> field + "<>''";
      case IS_ABSENT -> field + "=''";
      case EQUALS, NOT_EQUALS -> {
        values.add(value);
        yield field + (condition.operator() == FilterExpression.Operator.EQUALS ? "=?" : "<>?");
      }
      case IN, NOT_IN -> {
        values.addAll(condition.values());
        yield field
            + (condition.operator() == FilterExpression.Operator.IN ? " IN (" : " NOT IN (")
            + String.join(",", Collections.nCopies(condition.values().size(), "?"))
            + ")";
      }
      case CONTAINS, NOT_CONTAINS -> {
        values.add(value);
        yield "instr("
            + field
            + ",?)"
            + (condition.operator() == FilterExpression.Operator.CONTAINS ? ">0" : "=0");
      }
      case STARTS_WITH, NOT_STARTS_WITH -> {
        values.add(value);
        values.add(value);
        yield "substr("
            + field
            + ",1,length(?))"
            + (condition.operator() == FilterExpression.Operator.STARTS_WITH ? "=?" : "<>?");
      }
      case ENDS_WITH, NOT_ENDS_WITH -> {
        values.add(value);
        values.add(value);
        yield "substr("
            + field
            + ",length("
            + field
            + ")-length(?)+1)"
            + (condition.operator() == FilterExpression.Operator.ENDS_WITH ? "=?" : "<>?");
      }
      case REGEX, NOT_REGEX -> {
        values.add(value);
        yield "repro_regex(?,"
            + field
            + ")="
            + (condition.operator() == FilterExpression.Operator.REGEX ? "1" : "0");
      }
      default ->
          throw new IllegalArgumentException("Comparison fields require string filter operators.");
    };
  }
}

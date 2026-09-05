package com.holtherndon.bazelviz.core.filter;

import java.util.List;
import java.util.Objects;

/** Immutable visual-filter data shared by UI builders and query services; never query text. */
public sealed interface FilterExpression {

  int MAX_COMPONENTS = 64;
  int MAX_DEPTH = 8;
  int MAX_VALUES = 256;
  int MAX_VALUE_CHARACTERS = 4096;

  Group ALL = new Group(Junction.ALL, List.of());

  enum Junction {
    ALL,
    ANY
  }

  enum Operator {
    EQUALS("is"),
    NOT_EQUALS("is not"),
    IN("is one of"),
    NOT_IN("is not one of"),
    CONTAINS("contains"),
    NOT_CONTAINS("does not contain"),
    GREATER_THAN(">"),
    AT_LEAST("≥"),
    LESS_THAN("<"),
    AT_MOST("≤"),
    IS_PRESENT("is known"),
    IS_ABSENT("is unknown");

    private final String label;

    Operator(String label) {
      this.label = label;
    }

    public boolean requiresValue() {
      return this != IS_PRESENT && this != IS_ABSENT;
    }

    public boolean multipleValues() {
      return this == IN || this == NOT_IN;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  record Condition(String field, Operator operator, List<String> values)
      implements FilterExpression {
    public Condition {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(operator, "operator");
      values = List.copyOf(values);
      if (field.isBlank() || field.length() > MAX_VALUE_CHARACTERS) {
        throw new IllegalArgumentException("Choose a filter field.");
      }
      if ((!operator.requiresValue() && !values.isEmpty())
          || (operator.requiresValue() && values.isEmpty())
          || (!operator.multipleValues() && values.size() > 1)
          || values.size() > MAX_VALUES) {
        throw new IllegalArgumentException(
            "Choose the values required by this operator (at most " + MAX_VALUES + ").");
      }
      if (values.stream().anyMatch(value -> value.length() > MAX_VALUE_CHARACTERS)) {
        throw new IllegalArgumentException(
            "Filter values are limited to " + MAX_VALUE_CHARACTERS + " characters.");
      }
    }
  }

  record Group(Junction junction, List<FilterExpression> children) implements FilterExpression {
    public Group {
      Objects.requireNonNull(junction, "junction");
      children = List.copyOf(children);
      int count = 1;
      for (FilterExpression child : children) {
        count += child.components();
        if (child.depth() >= MAX_DEPTH) {
          throw new IllegalArgumentException(
              "Filters support at most " + MAX_DEPTH + " nested levels.");
        }
      }
      if (count > MAX_COMPONENTS) {
        throw new IllegalArgumentException(
            "Filters support at most " + MAX_COMPONENTS + " conditions and groups.");
      }
    }
  }

  default int components() {
    return switch (this) {
      case Condition ignored -> 1;
      case Group group ->
          1 + group.children().stream().mapToInt(FilterExpression::components).sum();
    };
  }

  default int depth() {
    return switch (this) {
      case Condition ignored -> 1;
      case Group group ->
          1 + group.children().stream().mapToInt(FilterExpression::depth).max().orElse(0);
    };
  }

  /** An empty visual group places no restriction on results, irrespective of its join mode. */
  default boolean isEmpty() {
    return this instanceof Group group
        && group.children().stream().allMatch(FilterExpression::isEmpty);
  }
}

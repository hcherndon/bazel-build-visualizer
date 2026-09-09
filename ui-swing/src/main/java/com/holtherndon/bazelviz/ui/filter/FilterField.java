package com.holtherndon.bazelviz.ui.filter;

import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import java.util.List;
import java.util.Objects;

/** A page supplies field labels, input types, choices and help; the builder owns no SQL. */
public record FilterField(String id, String label, Kind kind, List<Choice> choices, String help) {
  public enum Kind {
    TEXT,
    NUMBER,
    CHOICE,
    BOOLEAN
  }

  public record Choice(String value, String label) {
    public Choice {
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(label, "label");
    }

    @Override
    public String toString() {
      return label;
    }
  }

  public FilterField {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(kind, "kind");
    choices = List.copyOf(choices);
    Objects.requireNonNull(help, "help");
  }

  public List<Operator> operators() {
    return switch (kind) {
      case TEXT ->
          List.of(
              Operator.CONTAINS,
              Operator.NOT_CONTAINS,
              Operator.STARTS_WITH,
              Operator.NOT_STARTS_WITH,
              Operator.ENDS_WITH,
              Operator.NOT_ENDS_WITH,
              Operator.REGEX,
              Operator.NOT_REGEX,
              Operator.EQUALS,
              Operator.NOT_EQUALS,
              Operator.IN,
              Operator.NOT_IN,
              Operator.IS_PRESENT,
              Operator.IS_ABSENT);
      case NUMBER ->
          List.of(
              Operator.EQUALS,
              Operator.NOT_EQUALS,
              Operator.GREATER_THAN,
              Operator.AT_LEAST,
              Operator.LESS_THAN,
              Operator.AT_MOST,
              Operator.IS_PRESENT,
              Operator.IS_ABSENT);
      case CHOICE ->
          List.of(
              Operator.EQUALS,
              Operator.NOT_EQUALS,
              Operator.IN,
              Operator.NOT_IN,
              Operator.IS_PRESENT,
              Operator.IS_ABSENT);
      case BOOLEAN ->
          List.of(Operator.EQUALS, Operator.NOT_EQUALS, Operator.IS_PRESENT, Operator.IS_ABSENT);
    };
  }

  public String describeValue(String value) {
    return choices.stream()
        .filter(choice -> choice.value().equals(value))
        .map(Choice::label)
        .findFirst()
        .orElse(value);
  }

  @Override
  public String toString() {
    return label;
  }
}

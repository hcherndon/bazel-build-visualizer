package com.holtherndon.bazelviz.core.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.core.filter.FilterExpression.Condition;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Group;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Junction;
import com.holtherndon.bazelviz.core.filter.FilterExpression.Operator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterExpressionTest {
  @Test
  void groupsAreImmutableAndEmptyPlaceholdersDoNotRestrictResults() {
    List<FilterExpression> children = new ArrayList<>();
    Group group = new Group(Junction.ANY, children);
    children.add(condition());
    assertThat(group.children()).isEmpty();
    assertThat(group.isEmpty()).isTrue();
    assertThat(new Group(Junction.ALL, List.of(group)).isEmpty()).isTrue();
    assertThat(new Group(Junction.ALL, List.of(group, condition())).isEmpty()).isFalse();
  }

  @Test
  void operatorArityIsValidated() {
    assertThatThrownBy(() -> new Condition("type", Operator.IN, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Condition("type", Operator.EQUALS, List.of("7", "18")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Condition("type", Operator.IS_PRESENT, List.of("7")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new Condition("type", Operator.IS_ABSENT, List.of()).values()).isEmpty();
  }

  @Test
  void complexityAndValuesHaveExplicitLimits() {
    assertThat(
            new Group(
                    Junction.ALL,
                    Collections.nCopies(FilterExpression.MAX_COMPONENTS - 1, condition()))
                .components())
        .isEqualTo(FilterExpression.MAX_COMPONENTS);
    assertThatThrownBy(
            () ->
                new Group(
                    Junction.ALL,
                    Collections.nCopies(FilterExpression.MAX_COMPONENTS, condition())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conditions and groups");
    FilterExpression tree = condition();
    for (int depth = 1; depth < FilterExpression.MAX_DEPTH; depth++) {
      tree = new Group(Junction.ALL, List.of(tree));
    }
    FilterExpression deepest = tree;
    assertThat(deepest.depth()).isEqualTo(FilterExpression.MAX_DEPTH);
    assertThatThrownBy(() -> new Group(Junction.ANY, List.of(deepest)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nested levels");
    assertThatThrownBy(
            () ->
                new Condition(
                    "type", Operator.IN, Collections.nCopies(FilterExpression.MAX_VALUES + 1, "7")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Condition(
                    "type",
                    Operator.EQUALS,
                    List.of("x".repeat(FilterExpression.MAX_VALUE_CHARACTERS + 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("characters");
  }

  private static Condition condition() {
    return new Condition("children", Operator.GREATER_THAN, List.of("5"));
  }
}

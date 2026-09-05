package com.holtherndon.bazelviz.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

final class GraphResourceBudgetTest {

  @Test
  void exactBoundarySucceedsAndOneMoreIsRefusedWithState() throws Exception {
    GraphResourceBudget budget = new GraphResourceBudget(100);
    try (GraphResourceBudget.Reservation exact = budget.reserve(100, "mapped test index")) {
      assertThat(budget.snapshot().availableBytes()).isZero();
      assertThatThrownBy(() -> budget.reserve(1, "layout scratch"))
          .isInstanceOf(GraphResourceBudget.RefusedException.class)
          .hasMessageContaining("1 bytes")
          .hasMessageContaining("100 bytes")
          .hasMessageContaining("mapped test index");
    }
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }

  @Test
  void aggregateReservationIsAtomicAndCloseIsIdempotent() throws Exception {
    GraphResourceBudget budget = new GraphResourceBudget(10);
    assertThatThrownBy(
            () ->
                budget.reserveAll(
                    List.of(
                        new GraphResourceBudget.Request(6, "first"),
                        new GraphResourceBudget.Request(5, "second"))))
        .isInstanceOf(GraphResourceBudget.RefusedException.class);
    assertThat(budget.snapshot().retainedBytes()).isZero();

    GraphResourceBudget.Reservation reservation = budget.reserve(10, "exact");
    reservation.close();
    reservation.close();
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }

  @Test
  void negativeAndOverflowingRequestsNeverMutateTheBudget() {
    GraphResourceBudget budget = new GraphResourceBudget(Long.MAX_VALUE);
    assertThatThrownBy(() -> budget.reserve(-1, "negative"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                budget.reserveAll(
                    List.of(
                        new GraphResourceBudget.Request(Long.MAX_VALUE, "large"),
                        new GraphResourceBudget.Request(1, "overflow"))))
        .isInstanceOf(GraphResourceBudget.RefusedException.class);
    assertThat(budget.snapshot().retainedBytes()).isZero();
  }
}

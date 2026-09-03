package com.holtherndon.bazelviz.ui.starlark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.holtherndon.bazelviz.ui.session.StarlarkProfileReader;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class StarlarkRowSourceTest {

  @Test
  void functionPagesKeepTheQueryAndExactOffset() {
    FakeStarlarkProfileReader reader = new FakeStarlarkProfileReader();
    StarlarkProfileReader.FunctionQuery query =
        new StarlarkProfileReader.FunctionQuery(
            "", StarlarkProfileReader.FunctionSort.SELF_CPU, true);
    StarlarkFunctionRowSource source = StarlarkFunctionRowSource.open(reader, query);

    assertThat(source.rowCount()).isEqualTo(2);
    assertThat(source.fetchPage(1, 1).rows())
        .singleElement()
        .extracting(StarlarkProfileReader.HotFunction::name)
        .isEqualTo("load_config");
    assertThat(reader.queriedOnEdt).isFalse();
  }

  @Test
  void aShortNonFinalPageIsRejectedInsteadOfLookingComplete() {
    FakeStarlarkProfileReader reader =
        new FakeStarlarkProfileReader() {
          @Override
          public List<HotFunction> hotFunctions(FunctionQuery query, long offset, int limit) {
            return List.of();
          }
        };
    StarlarkFunctionRowSource source =
        StarlarkFunctionRowSource.open(
            reader,
            new StarlarkProfileReader.FunctionQuery(
                "", StarlarkProfileReader.FunctionSort.SELF_CPU, true));

    assertThatThrownBy(() -> source.fetchPage(0, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expected 1")
        .hasMessageContaining("exact total 2");
  }

  @Test
  void flameSliceRequiresEveryUnreturnedNodeToBeCounted() {
    assertThatThrownBy(
            () ->
                new StarlarkProfileReader.FlameSlice(
                    OptionalLong.empty(),
                    4,
                    1,
                    OptionalLong.of(100),
                    FakeStarlarkProfileReader.flame().nodes()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plus omitted");
  }
}

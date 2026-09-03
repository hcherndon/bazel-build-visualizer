package com.holtherndon.bazelviz.ui.table;

import java.util.Objects;
import java.util.function.Function;

/**
 * Binds one table column to a value extracted from a row object. The extractor runs on the EDT
 * during paint, so it must be cheap: field access or trivial formatting only, never I/O or parsing.
 */
public record ColumnSpec<T>(String name, Function<? super T, ?> extractor) {

  public ColumnSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(extractor, "extractor");
  }
}

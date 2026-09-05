package com.holtherndon.bazelviz.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One immutable keyset page with exact recorded-row counts from the same read snapshot. */
public record CountedPage<T, A>(
    List<T> rows, long totalRows, long remainingRows, Optional<A> nextAnchor) {

  public CountedPage {
    rows = List.copyOf(rows);
    Objects.requireNonNull(nextAnchor, "nextAnchor");
    if (totalRows < 0 || remainingRows < 0 || remainingRows > totalRows) {
      throw new IllegalArgumentException(
          "invalid page counts: total=" + totalRows + ", remaining=" + remainingRows);
    }
    if (rows.size() + remainingRows > totalRows) {
      throw new IllegalArgumentException(
          "page rows and remaining rows exceed total: rows="
              + rows.size()
              + ", remaining="
              + remainingRows
              + ", total="
              + totalRows);
    }
    if (rows.isEmpty() && remainingRows > 0) {
      throw new IllegalArgumentException("an empty page cannot leave recorded rows after it");
    }
    if ((remainingRows == 0) != nextAnchor.isEmpty()) {
      throw new IllegalArgumentException(
          "a next anchor must be present exactly when recorded rows remain");
    }
  }

  /** Exact number of rows at or before this page's end in the page's recorded snapshot. */
  public long shownThrough() {
    return totalRows - remainingRows;
  }
}

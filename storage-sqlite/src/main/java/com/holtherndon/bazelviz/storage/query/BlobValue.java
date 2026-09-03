package com.holtherndon.bazelviz.storage.query;

import java.util.Locale;

/**
 * Stands in for a BLOB column in a result grid, carrying its exact byte length.
 *
 * <p>A grid cell cannot show bytes, and {@code byte[].toString()} shows {@code [B@6d06d69c}, which
 * is a claim about nothing. Rule 12 forbids a silent drop, so what is shown states both that the
 * value is a blob and how large it is; the bytes themselves are reachable through the raw inspector
 * on the Events card, which is the view built for them.
 */
public record BlobValue(long byteLength) {

  public BlobValue {
    if (byteLength < 0) {
      throw new IllegalArgumentException("byteLength must be >= 0: " + byteLength);
    }
  }

  /** What a cell renders. */
  public String describe() {
    return String.format(Locale.ROOT, "BLOB, %,d bytes (not shown)", byteLength);
  }

  @Override
  public String toString() {
    return describe();
  }
}

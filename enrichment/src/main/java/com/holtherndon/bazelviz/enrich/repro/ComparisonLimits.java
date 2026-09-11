package com.holtherndon.bazelviz.enrich.repro;

/**
 * Admission and work limits for one disposable comparison, not a claim of complete build coverage.
 */
public record ComparisonLimits(
    long sourceBytes,
    long expandedBytes,
    int recordBytes,
    long records,
    long databaseBytes,
    long sqlSteps,
    int pageRows) {
  public static final long DEFAULT_SOURCE_BYTES = 512L * 1024 * 1024;
  public static final long DEFAULT_EXPANDED_BYTES = 2L * 1024 * 1024 * 1024;
  public static final int DEFAULT_RECORD_BYTES = 4 * 1024 * 1024;
  public static final long DEFAULT_RECORDS = 2_000_000;
  public static final long DEFAULT_DATABASE_BYTES = 1024L * 1024 * 1024;
  public static final long DEFAULT_SQL_STEPS = 500_000_000;
  public static final int DEFAULT_PAGE_ROWS = 500;
  public static final int MAX_CELL_CHARACTERS = 16_384;
  public static final int MAX_PAGE_CHARACTERS = 1_048_576;

  public ComparisonLimits {
    if (sourceBytes < 1
        || expandedBytes < 1
        || recordBytes < 1
        || records < 1
        || databaseBytes < 4096
        || sqlSteps < 1
        || pageRows < 1) {
      throw new IllegalArgumentException("Comparison limits must be positive.");
    }
  }

  public static ComparisonLimits defaults() {
    return new ComparisonLimits(
        DEFAULT_SOURCE_BYTES,
        DEFAULT_EXPANDED_BYTES,
        DEFAULT_RECORD_BYTES,
        DEFAULT_RECORDS,
        DEFAULT_DATABASE_BYTES,
        DEFAULT_SQL_STEPS,
        DEFAULT_PAGE_ROWS);
  }
}

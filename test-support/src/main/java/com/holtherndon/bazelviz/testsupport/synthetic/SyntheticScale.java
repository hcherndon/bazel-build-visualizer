package com.holtherndon.bazelviz.testsupport.synthetic;

/**
 * Benchmark tiers from plan section 20.1. Deterministic synthetic fixtures are generated at these
 * scales; the assumed system memory documents the machine class each tier targets, it is not
 * enforced.
 */
public enum SyntheticScale {
  TIER1(100_000L, 1_000_000L, 1_000_000L, 8),
  TIER2(1_000_000L, 10_000_000L, 20_000_000L, 16),
  TIER3(5_000_000L, 50_000_000L, 100_000_000L, 32);

  private final long actionCount;
  private final long eventCount;
  private final long edgeCount;
  private final int assumedSystemMemoryGb;

  SyntheticScale(long actionCount, long eventCount, long edgeCount, int assumedSystemMemoryGb) {
    this.actionCount = actionCount;
    this.eventCount = eventCount;
    this.edgeCount = edgeCount;
    this.assumedSystemMemoryGb = assumedSystemMemoryGb;
  }

  public long actionCount() {
    return actionCount;
  }

  public long eventCount() {
    return eventCount;
  }

  public long edgeCount() {
    return edgeCount;
  }

  public int assumedSystemMemoryGb() {
    return assumedSystemMemoryGb;
  }
}

package com.holtherndon.bazelviz.capture.bes;

import java.time.Duration;
import java.util.Objects;

/** Hard bounds for resources retained by one embedded BES server. */
public record BesResourceLimits(
    long retainedPayloadBytes,
    int maxConcurrentRpcs,
    int maxAdmittedStreamKeys,
    int handlerThreads,
    int handlerQueueCapacity,
    Duration quiescenceStablePeriod) {

  public BesResourceLimits {
    requirePositive(retainedPayloadBytes, "retainedPayloadBytes");
    requirePositive(maxConcurrentRpcs, "maxConcurrentRpcs");
    requirePositive(maxAdmittedStreamKeys, "maxAdmittedStreamKeys");
    requirePositive(handlerThreads, "handlerThreads");
    requirePositive(handlerQueueCapacity, "handlerQueueCapacity");
    Objects.requireNonNull(quiescenceStablePeriod, "quiescenceStablePeriod");
    if (quiescenceStablePeriod.isZero() || quiescenceStablePeriod.isNegative()) {
      throw new IllegalArgumentException(
          "quiescenceStablePeriod must be positive, got " + quiescenceStablePeriod);
    }
  }

  /** Balanced defaults for one desktop capture. */
  public static BesResourceLimits defaults() {
    return new BesResourceLimits(134_217_728L, 8, 64, 8, 64, Duration.ofMillis(250));
  }

  public BesResourceLimits withRetainedPayloadBytes(long value) {
    return new BesResourceLimits(
        value,
        maxConcurrentRpcs,
        maxAdmittedStreamKeys,
        handlerThreads,
        handlerQueueCapacity,
        quiescenceStablePeriod);
  }

  private static void requirePositive(long value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }
}

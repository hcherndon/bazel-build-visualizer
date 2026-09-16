package com.holtherndon.bazelviz.capture.live;

import com.holtherndon.bazelviz.core.journal.JournalFormat;
import java.time.Duration;

/**
 * Tunables for the live capture pipeline (plan 9.3 "initial tunable defaults", plan 9.4
 * backpressure).
 *
 * <p>Every queue here is bounded, without exception. An unbounded queue in front of a disk does not
 * prevent overload; it converts a capture that would have slowed down into one that runs the
 * application out of memory partway through a five-million-action build, having written nothing
 * useful.
 *
 * @param receiveQueueCapacity events accepted from the wire but not yet journaled. Small on
 *     purpose: this queue exists to absorb a burst, and a large one only delays the moment Bazel is
 *     told to slow down
 * @param normalizeQueueCapacity events journaled but not yet turned into rows. Larger, because
 *     normalization is the slower stage and its backlog does not risk losing anything — the frames
 *     are already durable
 * @param batchSize rows per SQLite transaction (plan 9.3: 5,000)
 * @param flushInterval how often a partial batch is committed anyway, so a slow build's events
 *     become visible rather than sitting in an uncommitted transaction (plan 9.3: at least every
 *     100 ms)
 * @param checkpointEveryFrames how often the resume point is written
 * @param progressInterval the fastest rate at which progress is published to a listener (plan 9.3:
 *     no faster than four times per second)
 * @param maxMessageBytes the largest payload accepted, matching the journal's
 * @param journalFlushInterval how often staged journal bytes are handed to the operating system.
 *     Not an fsync (plan 9.3 balanced durability): it bounds what a crash of this process can lose
 *     to the frames written since the last flush
 * @param deferAuxiliaryProcessing let the controlled audit own option inspection and preserve raw
 *     auxiliary files without ordinary post-build imports or graph queries; managed audits require
 *     a fresh app-owned compact execution log, preserve it without following a file symlink, and
 *     validate it with their bounded importer instead
 */
public record CaptureOptions(
    int receiveQueueCapacity,
    int normalizeQueueCapacity,
    int batchSize,
    Duration flushInterval,
    long checkpointEveryFrames,
    Duration progressInterval,
    int maxMessageBytes,
    Duration journalFlushInterval,
    boolean deferAuxiliaryProcessing) {

  /** Ordinary captures retain their existing post-build enrichment behavior. */
  public CaptureOptions(
      int receiveQueueCapacity,
      int normalizeQueueCapacity,
      int batchSize,
      Duration flushInterval,
      long checkpointEveryFrames,
      Duration progressInterval,
      int maxMessageBytes,
      Duration journalFlushInterval) {
    this(
        receiveQueueCapacity,
        normalizeQueueCapacity,
        batchSize,
        flushInterval,
        checkpointEveryFrames,
        progressInterval,
        maxMessageBytes,
        journalFlushInterval,
        false);
  }

  public CaptureOptions {
    requirePositive(receiveQueueCapacity, "receiveQueueCapacity");
    requirePositive(normalizeQueueCapacity, "normalizeQueueCapacity");
    requirePositive(batchSize, "batchSize");
    requirePositive(maxMessageBytes, "maxMessageBytes");
    if (checkpointEveryFrames < 1) {
      throw new IllegalArgumentException(
          "checkpointEveryFrames must be positive, got " + checkpointEveryFrames);
    }
    if (flushInterval.isNegative()
        || progressInterval.isNegative()
        || journalFlushInterval.isNegative()) {
      throw new IllegalArgumentException("intervals must not be negative");
    }
  }

  public static CaptureOptions defaults() {
    return new CaptureOptions(
        4_096,
        16_384,
        5_000,
        Duration.ofMillis(100),
        10_000,
        Duration.ofMillis(250),
        JournalFormat.DEFAULT_MAX_PAYLOAD_BYTES,
        Duration.ofMillis(500));
  }

  public CaptureOptions withReceiveQueueCapacity(int value) {
    return new CaptureOptions(
        value,
        normalizeQueueCapacity,
        batchSize,
        flushInterval,
        checkpointEveryFrames,
        progressInterval,
        maxMessageBytes,
        journalFlushInterval,
        deferAuxiliaryProcessing);
  }

  public CaptureOptions withNormalizeQueueCapacity(int value) {
    return new CaptureOptions(
        receiveQueueCapacity,
        value,
        batchSize,
        flushInterval,
        checkpointEveryFrames,
        progressInterval,
        maxMessageBytes,
        journalFlushInterval,
        deferAuxiliaryProcessing);
  }

  public CaptureOptions withBatchSize(int value) {
    return new CaptureOptions(
        receiveQueueCapacity,
        normalizeQueueCapacity,
        value,
        flushInterval,
        checkpointEveryFrames,
        progressInterval,
        maxMessageBytes,
        journalFlushInterval,
        deferAuxiliaryProcessing);
  }

  /** Preserve auxiliary raw files, but let the bounded audit importer inspect them separately. */
  public CaptureOptions withDeferredAuxiliaryProcessing() {
    return new CaptureOptions(
        receiveQueueCapacity,
        normalizeQueueCapacity,
        batchSize,
        flushInterval,
        checkpointEveryFrames,
        progressInterval,
        maxMessageBytes,
        journalFlushInterval,
        true);
  }

  private static void requirePositive(int value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be positive, got " + value);
    }
  }
}

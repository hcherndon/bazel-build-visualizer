package com.holtherndon.bazelviz.ui.session;

import com.holtherndon.bazelviz.capture.file.importer.ImportPhase;
import com.holtherndon.bazelviz.capture.file.importer.ImportProgress;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/**
 * Turns the importer's raw progress samples into something a progress view can render, without
 * inventing any of it.
 *
 * <p>No Swing types, no EDT assumptions: this is the piece the tests drive. The view is a thin
 * renderer of {@link Snapshot}.
 *
 * <h2>What is deliberately unknown</h2>
 *
 * <ul>
 *   <li><b>Percentage.</b> {@link ImportProgress#totalBytes()} is empty while the importer is
 *       detecting the format and while it is hashing and copying the source, and stays empty for
 *       any source whose size could not be determined. {@link Snapshot#determinate()} is false in
 *       exactly those cases and the bar must run indeterminate. Substituting "bytes read" for
 *       "bytes total" would produce a bar that sits at 100% for the whole import — a fabricated
 *       percentage, which plan 11.4 forbids.
 *   <li><b>Rate.</b> Empty until two samples are far enough apart in time to divide by. A rate
 *       computed across a sub-millisecond interval is noise, and a rate shown as 0 while the first
 *       block is being read is a lie. The last rate actually measured is carried forward between
 *       samples rather than being recomputed from a zero-length interval.
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #update} is called on the importing thread, one sample at a time, and returns an
 * immutable {@link Snapshot} that is safe to hand to the EDT. The mutable state is confined to the
 * importing thread.
 */
public final class ImportProgressModel {

  /** Shortest interval that yields a rate worth showing. */
  private static final long MIN_RATE_INTERVAL_NANOS = 50_000_000L; // 50 ms

  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  /**
   * One rendering of an import's progress.
   *
   * @param phase which stage produced the sample
   * @param recordsRead records journaled so far
   * @param bytesRead source bytes consumed so far
   * @param totalBytes source size, empty when it is genuinely not known
   * @param fractionComplete completed fraction, empty whenever {@code totalBytes} is
   * @param recordsPerSecond most recently measured record rate, empty before one could be measured
   * @param bytesPerSecond most recently measured byte rate, empty before one could be measured
   * @param elapsedNanos time since {@link #start()}
   * @param determinate true only when a real percentage exists
   */
  public record Snapshot(
      ImportPhase phase,
      long recordsRead,
      long bytesRead,
      OptionalLong totalBytes,
      OptionalDouble fractionComplete,
      OptionalDouble recordsPerSecond,
      OptionalDouble bytesPerSecond,
      long elapsedNanos,
      boolean determinate) {

    public Snapshot {
      Objects.requireNonNull(phase, "phase");
      Objects.requireNonNull(totalBytes, "totalBytes");
      Objects.requireNonNull(fractionComplete, "fractionComplete");
      Objects.requireNonNull(recordsPerSecond, "recordsPerSecond");
      Objects.requireNonNull(bytesPerSecond, "bytesPerSecond");
      if (determinate != fractionComplete.isPresent()) {
        throw new IllegalArgumentException("determinate must mean exactly 'a fraction is known'");
      }
    }
  }

  private final LongSupplier nanoClock;

  private long startedNanos;
  private long lastSampleNanos;
  private long lastRecords;
  private long lastBytes;
  private boolean started;
  private OptionalDouble recordsPerSecond = OptionalDouble.empty();
  private OptionalDouble bytesPerSecond = OptionalDouble.empty();
  private Snapshot snapshot = idle();

  public ImportProgressModel() {
    this(System::nanoTime);
  }

  /**
   * @param nanoClock monotonic clock source; the tests supply a fake one
   */
  public ImportProgressModel(LongSupplier nanoClock) {
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }

  /** Marks the start of a run and clears any rate measured by a previous one. */
  public void start() {
    startedNanos = nanoClock.getAsLong();
    lastSampleNanos = startedNanos;
    lastRecords = 0;
    lastBytes = 0;
    started = true;
    recordsPerSecond = OptionalDouble.empty();
    bytesPerSecond = OptionalDouble.empty();
    snapshot = idle();
  }

  /** Folds one sample in and returns the snapshot to render. */
  public Snapshot update(ImportProgress progress) {
    Objects.requireNonNull(progress, "progress");
    if (!started) {
      start();
    }
    long now = nanoClock.getAsLong();
    long sinceLast = now - lastSampleNanos;
    if (sinceLast >= MIN_RATE_INTERVAL_NANOS) {
      double seconds = sinceLast / NANOS_PER_SECOND;
      long deltaRecords = progress.recordsRead() - lastRecords;
      long deltaBytes = progress.bytesRead() - lastBytes;
      // A negative delta would mean the importer went backwards; that is
      // not a rate, so the previous measurement stands rather than being
      // replaced by a nonsense one.
      if (deltaRecords >= 0) {
        recordsPerSecond = OptionalDouble.of(deltaRecords / seconds);
      }
      if (deltaBytes >= 0) {
        bytesPerSecond = OptionalDouble.of(deltaBytes / seconds);
      }
      lastSampleNanos = now;
      lastRecords = progress.recordsRead();
      lastBytes = progress.bytesRead();
    }
    OptionalDouble fraction = progress.fractionComplete();
    snapshot =
        new Snapshot(
            progress.phase(),
            progress.recordsRead(),
            progress.bytesRead(),
            progress.totalBytes(),
            fraction,
            recordsPerSecond,
            bytesPerSecond,
            now - startedNanos,
            fraction.isPresent());
    return snapshot;
  }

  /** The most recent snapshot; an idle one before the first sample. */
  public Snapshot snapshot() {
    return snapshot;
  }

  private static Snapshot idle() {
    return new Snapshot(
        ImportPhase.DETECTING,
        0,
        0,
        OptionalLong.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        OptionalDouble.empty(),
        0,
        false);
  }
}

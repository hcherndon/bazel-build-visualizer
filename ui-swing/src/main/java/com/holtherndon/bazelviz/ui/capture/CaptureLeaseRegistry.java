package com.holtherndon.bazelviz.ui.capture;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-level exclusion for captures that address the same canonical repository.
 *
 * <p>The registry is thread-safe. Different keys can be leased concurrently. A lease handle owns
 * exactly the entry it acquired, so closing an old handle can never release a newer lease.
 */
public final class CaptureLeaseRegistry {

  private final ConcurrentMap<CaptureLeaseKey, Entry> active = new ConcurrentHashMap<>();
  private final Clock clock;

  public CaptureLeaseRegistry() {
    this(Clock.systemUTC());
  }

  CaptureLeaseRegistry(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Attempts to reserve one canonical execution/repository pair.
   *
   * <p>A conflict is an immutable snapshot. The reported lease may finish immediately after this
   * method returns, so callers may offer the user a retry without treating the snapshot as a
   * long-lived lock object.
   */
  public Acquisition tryAcquire(CaptureLeaseKey key, CaptureLeaseOwner owner) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(owner, "owner");
    Entry candidate = new Entry(new ActiveLease(key, owner, clock.instant()));
    Entry existing = active.putIfAbsent(key, candidate);
    if (existing != null) {
      return new Conflict(existing.details);
    }
    return new Granted(new LeaseHandle(this, candidate));
  }

  /** Returns a snapshot of the current owner, when the key is leased. */
  public Optional<ActiveLease> activeLease(CaptureLeaseKey key) {
    Objects.requireNonNull(key, "key");
    Entry entry = active.get(key);
    return entry == null ? Optional.empty() : Optional.of(entry.details);
  }

  /** Number of keys currently reserved. Intended for application status and diagnostics. */
  public int activeLeaseCount() {
    return active.size();
  }

  private void release(Entry entry) {
    active.remove(entry.details.key(), entry);
  }

  /** The result of a non-blocking acquisition attempt. */
  public sealed interface Acquisition permits Granted, Conflict {}

  /** A successful acquisition. Closing the handle releases it. */
  public record Granted(CaptureLease lease) implements Acquisition {
    public Granted {
      Objects.requireNonNull(lease, "lease");
    }
  }

  /** A failed acquisition, including the workspace that currently owns the key. */
  public record Conflict(ActiveLease activeLease) implements Acquisition {
    public Conflict {
      Objects.requireNonNull(activeLease, "activeLease");
    }
  }

  /** Immutable information about one active reservation. */
  public record ActiveLease(CaptureLeaseKey key, CaptureLeaseOwner owner, Instant acquiredAt) {
    public ActiveLease {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(acquiredAt, "acquiredAt");
    }
  }

  /** Idempotent ownership handle returned only for a successful acquisition. */
  public interface CaptureLease extends AutoCloseable {

    ActiveLease details();

    boolean isClosed();

    @Override
    void close();
  }

  private static final class Entry {
    private final ActiveLease details;

    private Entry(ActiveLease details) {
      this.details = details;
    }
  }

  private static final class LeaseHandle implements CaptureLease {
    private final CaptureLeaseRegistry registry;
    private final Entry entry;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LeaseHandle(CaptureLeaseRegistry registry, Entry entry) {
      this.registry = registry;
      this.entry = entry;
    }

    @Override
    public ActiveLease details() {
      return entry.details;
    }

    @Override
    public boolean isClosed() {
      return closed.get();
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        registry.release(entry);
      }
    }
  }
}

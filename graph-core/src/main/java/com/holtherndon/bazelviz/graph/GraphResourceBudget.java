package com.holtherndon.bazelviz.graph;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One aggregate admission budget for mapped indexes, retained renderings and graph scratch. */
public final class GraphResourceBudget {

  /** Default aggregate graph resource allowance for one open session. */
  public static final long DEFAULT_SESSION_BYTES = 1_073_741_824L;

  private final long limitBytes;
  private final Map<String, Long> retainedByPurpose = new LinkedHashMap<>();
  private long retainedBytes;

  public GraphResourceBudget() {
    this(DEFAULT_SESSION_BYTES);
  }

  public GraphResourceBudget(long limitBytes) {
    if (limitBytes < 0) {
      throw new IllegalArgumentException("negative graph resource budget " + limitBytes);
    }
    this.limitBytes = limitBytes;
  }

  /** Atomically admits one charge. Exact-boundary requests are allowed. */
  public Reservation reserve(long bytes, String purpose) throws RefusedException {
    return reserveAll(List.of(new Request(bytes, purpose))).getFirst();
  }

  /** Atomically admits all charges or admits none. */
  public synchronized List<Reservation> reserveAll(List<Request> requests) throws RefusedException {
    Objects.requireNonNull(requests, "requests");
    long requested = 0;
    for (Request request : requests) {
      Objects.requireNonNull(request, "requests contains null");
      if (request.bytes() < 0) {
        throw new IllegalArgumentException("negative graph resource request " + request.bytes());
      }
      if (request.purpose().isBlank()) {
        throw new IllegalArgumentException("graph resource purpose is blank");
      }
      try {
        requested = Math.addExact(requested, request.bytes());
      } catch (ArithmeticException overflow) {
        throw refused(Long.MAX_VALUE, describeRequests(requests));
      }
    }
    long after;
    try {
      after = Math.addExact(retainedBytes, requested);
    } catch (ArithmeticException overflow) {
      throw refused(requested, describeRequests(requests));
    }
    if (after > limitBytes) {
      throw refused(requested, describeRequests(requests));
    }

    List<Reservation> admitted = new ArrayList<>(requests.size());
    for (Request request : requests) {
      retainedByPurpose.merge(request.purpose(), request.bytes(), Math::addExact);
      admitted.add(new Reservation(this, request.bytes(), request.purpose()));
    }
    retainedBytes = after;
    return Collections.unmodifiableList(admitted);
  }

  public synchronized Snapshot snapshot() {
    return new Snapshot(
        limitBytes,
        retainedBytes,
        limitBytes - retainedBytes,
        Collections.unmodifiableMap(new LinkedHashMap<>(retainedByPurpose)));
  }

  private RefusedException refused(long requested, String purpose) {
    return new RefusedException(requested, purpose, snapshot());
  }

  private static String describeRequests(List<Request> requests) {
    StringBuilder out = new StringBuilder();
    for (Request request : requests) {
      if (!out.isEmpty()) {
        out.append(" + ");
      }
      out.append(request.purpose()).append(" (").append(request.bytes()).append(" bytes)");
    }
    return out.toString();
  }

  private synchronized void release(Reservation reservation) {
    if (reservation.released) {
      return;
    }
    reservation.released = true;
    retainedBytes -= reservation.bytes;
    retainedByPurpose.computeIfPresent(
        reservation.purpose,
        (ignored, current) -> {
          long remaining = current - reservation.bytes;
          return remaining == 0 ? null : remaining;
        });
  }

  /** One requested charge. */
  public record Request(long bytes, String purpose) {
    public Request {
      Objects.requireNonNull(purpose, "purpose");
    }
  }

  /** Current exact aggregate state. */
  public record Snapshot(
      long limitBytes,
      long retainedBytes,
      long availableBytes,
      Map<String, Long> retainedByPurpose) {}

  /** A charge whose close releases it exactly once. */
  public static final class Reservation implements AutoCloseable {
    private final GraphResourceBudget owner;
    private final long bytes;
    private final String purpose;
    private boolean released;

    private Reservation(GraphResourceBudget owner, long bytes, String purpose) {
      this.owner = owner;
      this.bytes = bytes;
      this.purpose = purpose;
    }

    public long bytes() {
      return bytes;
    }

    public String purpose() {
      return purpose;
    }

    @Override
    public void close() {
      owner.release(this);
    }
  }

  /** Visible, typed refusal carrying the request and retained state. */
  public static final class RefusedException extends IOException {
    private static final long serialVersionUID = 1L;

    private final long requestedBytes;
    private final Snapshot snapshot;

    RefusedException(long requestedBytes, String purpose, Snapshot snapshot) {
      super(
          "Graph work refused: "
              + purpose
              + " requests "
              + requestedBytes
              + " bytes; the session budget is "
              + snapshot.limitBytes()
              + " bytes with "
              + snapshot.retainedBytes()
              + " retained ("
              + snapshot.retainedByPurpose()
              + ").");
      this.requestedBytes = requestedBytes;
      this.snapshot = snapshot;
    }

    public long requestedBytes() {
      return requestedBytes;
    }

    public Snapshot snapshot() {
      return snapshot;
    }
  }
}
